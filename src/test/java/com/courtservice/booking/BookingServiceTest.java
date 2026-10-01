package com.courtservice.booking;

import com.courtservice.booking.cache.SoldOutSessionCache;
import com.courtservice.booking.dto.BookingCreateRequest;
import com.courtservice.booking.entity.Booking;
import com.courtservice.booking.repository.BookingRepository;
import com.courtservice.booking.service.BookingService;
import com.courtservice.booking.strategy.BookingConcurrencyMode;
import com.courtservice.booking.strategy.BookingOutcome;
import com.courtservice.booking.config.BookingProperties;
import com.courtservice.booking.strategy.BookingStrategy;
import com.courtservice.booking.strategy.BookingStrategyRegistry;
import com.courtservice.common.error.BusinessException;
import com.courtservice.common.error.ErrorCode;
import com.courtservice.member.MemberService;
import com.courtservice.session.CourtSessionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers what {@code create} adds around the strategy: the translation of the booking unique
 * constraint, which only a real race can trigger, and the sold-out flag. Stubbing the strategy
 * makes both deterministic.
 */
class BookingServiceTest {

    private static final long SESSION_ID = 1L;
    private static final long MEMBER_ID = 2L;
    private static final int CAPACITY = 10;
    private static final BookingCreateRequest REQUEST = new BookingCreateRequest(SESSION_ID, MEMBER_ID);
    private static final BookingProperties PROPERTIES = new BookingProperties(BookingConcurrencyMode.SYNCHRONIZED,
            new BookingProperties.OptimisticRetry(1, Duration.ZERO, Duration.ZERO),
            new BookingProperties.SoldOutCache(true, Duration.ofMinutes(1), 100));

    private BookingStrategy strategy;
    private SoldOutSessionCache soldOutSessionCache;
    private BookingService bookingService;

    @BeforeEach
    void setUp() {
        strategy = mock(BookingStrategy.class);
        BookingStrategyRegistry registry = mock(BookingStrategyRegistry.class);
        when(registry.active()).thenReturn(strategy);
        soldOutSessionCache = new SoldOutSessionCache(PROPERTIES, new SimpleMeterRegistry());
        bookingService = new BookingService(mock(BookingRepository.class), mock(CourtSessionService.class),
                mock(MemberService.class), registry, soldOutSessionCache);
    }

    @Test
    void createTranslatesTheSessionMemberUniqueViolationIntoDuplicateBooking() {
        // given
        when(strategy.book(SESSION_ID, MEMBER_ID))
                .thenThrow(constraintViolation(Booking.SESSION_MEMBER_UNIQUE_CONSTRAINT));

        // when / then
        assertThatThrownBy(() -> bookingService.create(REQUEST))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE_BOOKING);
    }

    @Test
    void createRethrowsOtherConstraintViolationsUnchanged() {
        // given
        DataIntegrityViolationException violation = constraintViolation("booking_member_id_fkey");
        when(strategy.book(SESSION_ID, MEMBER_ID)).thenThrow(violation);

        // when / then
        assertThatThrownBy(() -> bookingService.create(REQUEST)).isSameAs(violation);
    }

    @Test
    void flaggedSessionIsRejectedWithoutCallingTheStrategy() {
        // given
        soldOutSessionCache.markSoldOut(SESSION_ID);

        // when / then
        assertThatThrownBy(() -> bookingService.create(REQUEST))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SESSION_FULL);
        verifyNoInteractions(strategy);
    }

    @Test
    void sessionFullFromTheStrategyFlagsTheSession() {
        // given
        when(strategy.book(SESSION_ID, MEMBER_ID))
                .thenThrow(new BusinessException(ErrorCode.SESSION_FULL, ErrorCode.SESSION_FULL.getDefaultDetail()));

        // when / then
        assertThatThrownBy(() -> bookingService.create(REQUEST))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SESSION_FULL);
        assertThat(soldOutSessionCache.isSoldOut(SESSION_ID)).isTrue();
    }

    @Test
    void bookingTheLastSeatFlagsTheSession() {
        // given
        when(strategy.book(SESSION_ID, MEMBER_ID)).thenReturn(outcome(CAPACITY));

        // when
        bookingService.create(REQUEST);

        // then
        assertThat(soldOutSessionCache.isSoldOut(SESSION_ID)).isTrue();
    }

    @Test
    void bookingWhileSeatsRemainLeavesTheSessionUnflagged() {
        // given
        when(strategy.book(SESSION_ID, MEMBER_ID)).thenReturn(outcome(CAPACITY - 1));

        // when
        bookingService.create(REQUEST);

        // then
        assertThat(soldOutSessionCache.isSoldOut(SESSION_ID)).isFalse();
    }

    private static BookingOutcome outcome(int bookedCount) {
        return new BookingOutcome(mock(Booking.class, RETURNS_DEEP_STUBS), CAPACITY, bookedCount);
    }

    private static DataIntegrityViolationException constraintViolation(String constraintName) {
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("constraint violated", new SQLException(), constraintName));
    }
}
