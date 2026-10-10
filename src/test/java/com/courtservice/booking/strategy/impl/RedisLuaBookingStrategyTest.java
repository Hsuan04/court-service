package com.courtservice.booking.strategy.impl;

import com.courtservice.booking.entity.BookingStatus;
import com.courtservice.booking.repository.BookingRepository;
import com.courtservice.booking.stock.SeatStockScripts;
import com.courtservice.booking.stock.SeatStockScripts.Reservation;
import com.courtservice.booking.strategy.BookingOutcome;
import com.courtservice.common.error.BusinessException;
import com.courtservice.common.error.ErrorCode;
import com.courtservice.member.MemberService;
import com.courtservice.session.BookedCountSnapshot;
import com.courtservice.session.CourtSession;
import com.courtservice.session.CourtSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drives the reservation handling around the transaction with a mocked {@link SeatStockScripts},
 * so the lazy initialization and the rules for undoing a reservation are asserted without Redis
 * or a database.
 */
class RedisLuaBookingStrategyTest {

    private static final long SESSION_ID = 1L;
    private static final long MEMBER_ID = 2L;
    private static final long EARLIER_MEMBER_ID = 3L;
    private static final int CAPACITY = 10;
    private static final int BOOKED = 1;

    private CourtSessionService courtSessionService;
    private BookingRepository bookingRepository;
    private TransactionTemplate transactionTemplate;
    private SeatStockScripts scripts;
    private RedisLuaBookingStrategy strategy;

    @BeforeEach
    void setUp() {
        courtSessionService = mock(CourtSessionService.class);
        bookingRepository = mock(BookingRepository.class);
        transactionTemplate = mock(TransactionTemplate.class);
        scripts = mock(SeatStockScripts.class);
        strategy = new RedisLuaBookingStrategy(courtSessionService, mock(MemberService.class), bookingRepository,
                transactionTemplate, scripts);
    }

    @Test
    void reservedSeatIsWrittenToTheDatabaseAndKept() {
        // given
        BookingOutcome outcome = new BookingOutcome(null, CAPACITY, BOOKED);
        when(scripts.reserve(SESSION_ID, MEMBER_ID)).thenReturn(Reservation.RESERVED);
        when(transactionTemplate.execute(anyCallback())).thenReturn(outcome);

        // when
        BookingOutcome result = strategy.book(SESSION_ID, MEMBER_ID);

        // then
        assertThat(result).isSameAs(outcome);
        verify(scripts, never()).release(anyLong(), anyLong(), anyBoolean());
    }

    @Test
    void missingStockIsInitializedFromTheDatabaseThenReserved() {
        // given: a session with one earlier booking, and Redis that does not know it yet
        CourtSession session = mock(CourtSession.class);
        when(session.getCapacity()).thenReturn(CAPACITY);
        when(session.getBookedCount()).thenReturn(BOOKED);
        when(courtSessionService.findSession(SESSION_ID)).thenReturn(session);
        when(bookingRepository.findMemberIdsByCourtSessionIdAndStatus(SESSION_ID, BookingStatus.CONFIRMED)).thenReturn(List.of(EARLIER_MEMBER_ID));
        when(courtSessionService.incrementBookedCountIfAvailable(SESSION_ID))
                .thenReturn(Optional.of(snapshot(CAPACITY, BOOKED + 1)));
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                invocation.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
        when(scripts.reserve(SESSION_ID, MEMBER_ID)).thenReturn(Reservation.NOT_INITIALIZED, Reservation.RESERVED);

        // when
        BookingOutcome result = strategy.book(SESSION_ID, MEMBER_ID);

        // then
        verify(scripts).initialize(SESSION_ID, CAPACITY - BOOKED, List.of(EARLIER_MEMBER_ID));
        assertThat(result.bookedCount()).isEqualTo(BOOKED + 1);
    }

    @Test
    void noSeatLeftIsRejectedWithoutTouchingTheDatabase() {
        // given
        when(scripts.reserve(SESSION_ID, MEMBER_ID)).thenReturn(Reservation.NO_SEAT_LEFT);

        // when / then
        assertErrorCode(ErrorCode.SESSION_FULL);
        verify(transactionTemplate, never()).execute(anyCallback());
    }

    @Test
    void memberHoldingASeatIsRejectedAsDuplicateWithoutTouchingTheDatabase() {
        // given
        when(scripts.reserve(SESSION_ID, MEMBER_ID)).thenReturn(Reservation.ALREADY_RESERVED);

        // when / then
        assertErrorCode(ErrorCode.DUPLICATE_BOOKING);
        verify(transactionTemplate, never()).execute(anyCallback());
    }

    @Test
    void stockMissingAgainAfterInitializationFailsLoudly() {
        // given
        CourtSession session = mock(CourtSession.class);
        when(courtSessionService.findSession(SESSION_ID)).thenReturn(session);
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                invocation.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
        when(scripts.reserve(SESSION_ID, MEMBER_ID)).thenReturn(Reservation.NOT_INITIALIZED);

        // when / then
        assertThatThrownBy(() -> strategy.book(SESSION_ID, MEMBER_ID)).isInstanceOf(IllegalStateException.class);
        verify(scripts, never()).release(anyLong(), anyLong(), anyBoolean());
    }

    @Test
    void businessFailureInTheDatabaseUndoesTheReservationWithItsSeat() {
        // given
        BusinessException notFound = new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                ErrorCode.RESOURCE_NOT_FOUND.getDefaultDetail());
        when(scripts.reserve(SESSION_ID, MEMBER_ID)).thenReturn(Reservation.RESERVED);
        when(transactionTemplate.execute(anyCallback())).thenThrow(notFound);

        // when / then
        assertThatThrownBy(() -> strategy.book(SESSION_ID, MEMBER_ID)).isSameAs(notFound);
        verify(scripts).release(SESSION_ID, MEMBER_ID, true);
    }

    @Test
    void unexpectedFailureInTheDatabaseUndoesTheReservationWithItsSeat() {
        // given
        IllegalStateException failure = new IllegalStateException("database unavailable");
        when(scripts.reserve(SESSION_ID, MEMBER_ID)).thenReturn(Reservation.RESERVED);
        when(transactionTemplate.execute(anyCallback())).thenThrow(failure);

        // when / then
        assertThatThrownBy(() -> strategy.book(SESSION_ID, MEMBER_ID)).isSameAs(failure);
        verify(scripts).release(SESSION_ID, MEMBER_ID, true);
    }

    /** Redis granted a seat the database does not have: only the member is removed. */
    @Test
    void fullDatabaseRemovesTheMemberButKeepsTheSeatOutOfTheStock() {
        // given
        BusinessException full = new BusinessException(ErrorCode.SESSION_FULL, ErrorCode.SESSION_FULL.getDefaultDetail());
        when(scripts.reserve(SESSION_ID, MEMBER_ID)).thenReturn(Reservation.RESERVED);
        when(transactionTemplate.execute(anyCallback())).thenThrow(full);

        // when / then
        assertThatThrownBy(() -> strategy.book(SESSION_ID, MEMBER_ID)).isSameAs(full);
        verify(scripts).release(SESSION_ID, MEMBER_ID, false);
    }

    @Test
    void failureToUndoTheReservationKeepsTheOriginalException() {
        // given
        IllegalStateException failure = new IllegalStateException("database unavailable");
        IllegalStateException redisDown = new IllegalStateException("Redis unavailable");
        when(scripts.reserve(SESSION_ID, MEMBER_ID)).thenReturn(Reservation.RESERVED);
        when(transactionTemplate.execute(anyCallback())).thenThrow(failure);
        when(scripts.release(SESSION_ID, MEMBER_ID, true)).thenThrow(redisDown);

        // when / then
        assertThatThrownBy(() -> strategy.book(SESSION_ID, MEMBER_ID))
                .isSameAs(failure)
                .hasSuppressedException(redisDown);
    }

    private void assertErrorCode(ErrorCode expected) {
        assertThatThrownBy(() -> strategy.book(SESSION_ID, MEMBER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
    }

    private static BookedCountSnapshot snapshot(int capacity, int bookedCount) {
        return new BookedCountSnapshot() {
            @Override
            public int getBookedCount() {
                return bookedCount;
            }

            @Override
            public int getCapacity() {
                return capacity;
            }
        };
    }

    private static TransactionCallback<BookingOutcome> anyCallback() {
        return any();
    }
}
