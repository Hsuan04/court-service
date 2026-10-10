package com.courtservice.booking.strategy.impl;

import com.courtservice.booking.config.BookingProperties;
import com.courtservice.booking.repository.BookingRepository;
import com.courtservice.booking.strategy.BookingConcurrencyMode;
import com.courtservice.booking.strategy.BookingOutcome;
import com.courtservice.common.error.BusinessException;
import com.courtservice.common.error.ErrorCode;
import com.courtservice.member.MemberService;
import com.courtservice.session.CourtSessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drives the per-session lock around a mocked transaction, holding a session's lock from another
 * thread where needed, so timeouts, independence of sessions and release after failure are
 * asserted without a database.
 */
class JvmLockBookingStrategyTest {

    private static final long SESSION_ID = 1L;
    private static final long OTHER_SESSION_ID = 2L;
    private static final long MEMBER_ID = 3L;
    private static final int CAPACITY = 10;
    private static final Duration SHORT_WAIT = Duration.ofMillis(100);

    private TransactionTemplate transactionTemplate;
    private JvmLockBookingStrategy strategy;

    @BeforeEach
    void setUp() {
        transactionTemplate = mock(TransactionTemplate.class);
        BookingProperties properties = new BookingProperties(BookingConcurrencyMode.JVM_LOCK,
                new BookingProperties.OptimisticRetry(1, Duration.ZERO, Duration.ZERO),
                new BookingProperties.SoldOutCache(false, Duration.ZERO, 1),
                new BookingProperties.DistributedLock(Duration.ZERO, Duration.ZERO),
                new BookingProperties.JvmLock(SHORT_WAIT));
        strategy = new JvmLockBookingStrategy(mock(CourtSessionService.class), mock(MemberService.class),
                mock(BookingRepository.class), transactionTemplate, properties);
    }

    @AfterEach
    void clearInterruptFlag() {
        // A test that interrupts the thread must not leak the flag into the next test.
        Thread.interrupted();
    }

    @Test
    void bookingRunsTheTransactionAndReleasesTheLock() {
        // given
        BookingOutcome outcome = new BookingOutcome(null, CAPACITY, 1);
        when(transactionTemplate.execute(anyCallback())).thenReturn(outcome);

        // when
        BookingOutcome result = strategy.book(SESSION_ID, MEMBER_ID);

        // then
        assertThat(result).isSameAs(outcome);
        assertThat(strategy.lockFor(SESSION_ID).isLocked()).isFalse();
    }

    @Test
    void bookingTimesOutWhileAnotherThreadHoldsTheSessionLock() throws Exception {
        // given
        try (HeldLock ignored = HeldLock.hold(strategy.lockFor(SESSION_ID))) {
            // when / then
            assertThatThrownBy(() -> strategy.book(SESSION_ID, MEMBER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.LOCK_TIMEOUT);
        }
        verify(transactionTemplate, never()).execute(anyCallback());
    }

    @Test
    void lockOfOneSessionDoesNotBlockBookingsOfAnother() throws Exception {
        // given
        when(transactionTemplate.execute(anyCallback())).thenReturn(new BookingOutcome(null, CAPACITY, 1));

        try (HeldLock ignored = HeldLock.hold(strategy.lockFor(SESSION_ID))) {
            // when
            strategy.book(OTHER_SESSION_ID, MEMBER_ID);
        }

        // then
        verify(transactionTemplate).execute(anyCallback());
    }

    @Test
    void lockIsReleasedWhenTheTransactionFails() {
        // given
        BusinessException full = new BusinessException(ErrorCode.SESSION_FULL, ErrorCode.SESSION_FULL.getDefaultDetail());
        when(transactionTemplate.execute(anyCallback())).thenThrow(full);

        // when / then
        assertThatThrownBy(() -> strategy.book(SESSION_ID, MEMBER_ID)).isSameAs(full);
        assertThat(strategy.lockFor(SESSION_ID).isLocked()).isFalse();
    }

    /** The lock in use is the one every later booking of the session gets, never a replacement. */
    @Test
    void sessionKeepsOneLockWhileItIsReferenced() {
        // given
        ReentrantLock first = strategy.lockFor(SESSION_ID);

        // when
        ReentrantLock second = strategy.lockFor(SESSION_ID);

        // then
        assertThat(second).isSameAs(first);
        assertThat(strategy.lockFor(OTHER_SESSION_ID)).isNotSameAs(first);
    }

    @Test
    void interruptedWhileWaitingThrowsAndRestoresTheInterruptFlag() {
        // given
        Thread.currentThread().interrupt();

        // when / then
        assertThatThrownBy(() -> strategy.book(SESSION_ID, MEMBER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(InterruptedException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verify(transactionTemplate, never()).execute(anyCallback());
    }

    private static TransactionCallback<BookingOutcome> anyCallback() {
        return any();
    }

    /**
     * Holds a lock from a dedicated thread until closed. A {@link ReentrantLock} is re-entrant for
     * its owner, so the test thread itself could always take it again.
     */
    private static final class HeldLock implements AutoCloseable {

        private final ExecutorService owner;
        private final ReentrantLock lock;

        private HeldLock(ExecutorService owner, ReentrantLock lock) {
            this.owner = owner;
            this.lock = lock;
        }

        static HeldLock hold(ReentrantLock lock) throws ExecutionException, InterruptedException {
            ExecutorService owner = Executors.newSingleThreadExecutor();
            owner.submit(lock::lock).get();
            return new HeldLock(owner, lock);
        }

        @Override
        public void close() throws ExecutionException, InterruptedException {
            owner.submit(lock::unlock).get();
            owner.shutdown();
        }
    }
}
