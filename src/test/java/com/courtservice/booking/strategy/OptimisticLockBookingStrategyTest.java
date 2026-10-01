package com.courtservice.booking.strategy;

import com.courtservice.booking.config.BookingProperties;
import com.courtservice.booking.repository.BookingRepository;
import com.courtservice.booking.strategy.impl.OptimisticLockBookingStrategy;
import com.courtservice.common.error.BusinessException;
import com.courtservice.common.error.ErrorCode;
import com.courtservice.court.Court;
import com.courtservice.member.MemberService;
import com.courtservice.session.CourtSession;
import com.courtservice.session.CourtSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drives the retry loop by stubbing the outcome of each transaction, so retry counts and the
 * give-up decision are asserted deterministically without real contention.
 */
class OptimisticLockBookingStrategyTest {

    private static final long SESSION_ID = 1L;
    private static final long MEMBER_ID = 2L;
    private static final int CAPACITY = 10;
    private static final OffsetDateTime OPEN_AT = OffsetDateTime.of(2026, 9, 25, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final int MAX_ATTEMPTS = 5;

    private static final BookingProperties.SoldOutCache SOLD_OUT_FLAG_OFF =
            new BookingProperties.SoldOutCache(false, Duration.ZERO, 1);

    /** No wait between attempts, so the retry loop runs instantly. */
    private static final BookingProperties PROPERTIES = new BookingProperties(BookingConcurrencyMode.OPTIMISTIC_LOCK,
            new BookingProperties.OptimisticRetry(MAX_ATTEMPTS, Duration.ZERO, Duration.ZERO), SOLD_OUT_FLAG_OFF);

    private CourtSessionService courtSessionService;
    private TransactionTemplate transactionTemplate;
    private OptimisticLockBookingStrategy strategy;

    @BeforeEach
    void setUp() {
        courtSessionService = mock(CourtSessionService.class);
        transactionTemplate = mock(TransactionTemplate.class);
        strategy = new OptimisticLockBookingStrategy(courtSessionService, mock(MemberService.class),
                mock(BookingRepository.class), transactionTemplate, PROPERTIES);
    }

    @Test
    void retriesAfterVersionConflictsAndReturnsTheSuccessfulAttempt() {
        // given
        BookingOutcome outcome = new BookingOutcome(null, CAPACITY, 1);
        when(transactionTemplate.execute(anyCallback()))
                .thenThrow(versionConflict())
                .thenThrow(versionConflict())
                .thenReturn(outcome);

        // when
        BookingOutcome result = strategy.book(SESSION_ID, MEMBER_ID);

        // then
        assertThat(result).isSameAs(outcome);
        verify(transactionTemplate, times(3)).execute(anyCallback());
    }

    @Test
    void givesUpWithSessionFullWhenTheSessionFilledUpWhileRetrying() {
        // given
        when(transactionTemplate.execute(anyCallback())).thenThrow(versionConflict());
        when(courtSessionService.findSession(SESSION_ID)).thenReturn(sessionWithBookings(CAPACITY));

        // when / then
        assertThatThrownBy(() -> strategy.book(SESSION_ID, MEMBER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SESSION_FULL);
        verify(transactionTemplate, times(MAX_ATTEMPTS)).execute(anyCallback());
    }

    @Test
    void givesUpWithConcurrentModificationWhileSeatsRemain() {
        // given
        when(transactionTemplate.execute(anyCallback())).thenThrow(versionConflict());
        when(courtSessionService.findSession(SESSION_ID)).thenReturn(sessionWithBookings(CAPACITY - 1));

        // when / then
        assertThatThrownBy(() -> strategy.book(SESSION_ID, MEMBER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONCURRENT_MODIFICATION);
        verify(transactionTemplate, times(MAX_ATTEMPTS)).execute(anyCallback());
    }

    @Test
    void neverRetriesABusinessRuleViolation() {
        // given
        when(transactionTemplate.execute(anyCallback()))
                .thenThrow(new BusinessException(ErrorCode.SESSION_FULL, ErrorCode.SESSION_FULL.getDefaultDetail()));

        // when / then
        assertThatThrownBy(() -> strategy.book(SESSION_ID, MEMBER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SESSION_FULL);
        verify(transactionTemplate, times(1)).execute(anyCallback());
    }

    private static TransactionCallback<BookingOutcome> anyCallback() {
        return any();
    }

    private static ObjectOptimisticLockingFailureException versionConflict() {
        return new ObjectOptimisticLockingFailureException(CourtSession.class, SESSION_ID);
    }

    private static CourtSession sessionWithBookings(int bookedCount) {
        CourtSession session = new CourtSession(new Court("Court A", null), LocalDate.of(2026, 10, 1),
                LocalTime.of(18, 0), LocalTime.of(20, 0), CAPACITY, OPEN_AT, OPEN_AT.plusDays(5));
        ReflectionTestUtils.setField(session, "bookedCount", bookedCount);
        return session;
    }
}
