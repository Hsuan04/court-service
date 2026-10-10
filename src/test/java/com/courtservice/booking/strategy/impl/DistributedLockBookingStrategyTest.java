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
import org.mockito.InOrder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drives the lock handling around the transaction with a mocked {@link RLock}, so acquisition
 * failures, interruption and a lock lost before release are asserted without Redis.
 */
class DistributedLockBookingStrategyTest {

    private static final long SESSION_ID = 1L;
    private static final long MEMBER_ID = 2L;
    private static final int CAPACITY = 10;
    private static final String LOCK_KEY = "lock:court-session:1";
    private static final Duration WAIT_TIME = Duration.ofSeconds(3);
    private static final Duration DEBUG_HOLD = Duration.ofMillis(200);
    private static final double NANOS_PER_MILLI = 1_000_000.0;

    private RedissonClient redissonClient;
    private RLock lock;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        redissonClient = mock(RedissonClient.class);
        lock = mock(RLock.class);
        transactionTemplate = mock(TransactionTemplate.class);
        when(redissonClient.getLock(LOCK_KEY)).thenReturn(lock);
    }

    @AfterEach
    void clearInterruptFlag() {
        // A test that interrupts the thread must not leak the flag into the next test.
        Thread.interrupted();
    }

    @Test
    void acquiredLockWrapsTheTransactionAndIsReleasedAfterIt() throws InterruptedException {
        // given
        BookingOutcome outcome = new BookingOutcome(null, CAPACITY, 1);
        when(lock.tryLock(WAIT_TIME.toMillis(), TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(transactionTemplate.execute(anyCallback())).thenReturn(outcome);

        // when
        BookingOutcome result = strategy(Duration.ZERO).book(SESSION_ID, MEMBER_ID);

        // then
        assertThat(result).isSameAs(outcome);
        verify(redissonClient).getLock(LOCK_KEY);
        InOrder order = inOrder(lock, transactionTemplate);
        order.verify(lock).tryLock(WAIT_TIME.toMillis(), TimeUnit.MILLISECONDS);
        order.verify(transactionTemplate).execute(anyCallback());
        order.verify(lock, times(1)).unlock();
    }

    @Test
    void lockNotAcquiredInTimeIsRejectedWithLockTimeoutWithoutTransaction() throws InterruptedException {
        // given
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(false);

        // when / then
        assertThatThrownBy(() -> strategy(Duration.ZERO).book(SESSION_ID, MEMBER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.LOCK_TIMEOUT);
        verify(transactionTemplate, never()).execute(anyCallback());
        verify(lock, never()).unlock();
    }

    @Test
    void lockIsReleasedWhenTheTransactionFails() throws InterruptedException {
        // given
        BusinessException full = new BusinessException(ErrorCode.SESSION_FULL, ErrorCode.SESSION_FULL.getDefaultDetail());
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(transactionTemplate.execute(anyCallback())).thenThrow(full);

        // when / then
        assertThatThrownBy(() -> strategy(Duration.ZERO).book(SESSION_ID, MEMBER_ID)).isSameAs(full);
        verify(lock, times(1)).unlock();
    }

    @Test
    void interruptedWhileWaitingThrowsAndRestoresTheInterruptFlag() throws InterruptedException {
        // given
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenThrow(new InterruptedException());

        // when / then
        assertThatThrownBy(() -> strategy(Duration.ZERO).book(SESSION_ID, MEMBER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(InterruptedException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verify(transactionTemplate, never()).execute(anyCallback());
    }

    @Test
    void lockLostBeforeReleaseIsNotUnlockedAndKeepsTheOriginalException() throws InterruptedException {
        // given
        IllegalStateException original = new IllegalStateException("database unavailable");
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(false);
        when(transactionTemplate.execute(anyCallback())).thenThrow(original);

        // when / then
        assertThatThrownBy(() -> strategy(Duration.ZERO).book(SESSION_ID, MEMBER_ID)).isSameAs(original);
        verify(lock, never()).unlock();
    }

    @Test
    void debugHoldDelaysTheTransactionWhileTheLockIsHeld() throws InterruptedException {
        // given
        AtomicLong lockedAt = new AtomicLong();
        AtomicLong transactionStartedAt = new AtomicLong();
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenAnswer(invocation -> {
            lockedAt.set(System.nanoTime());
            return true;
        });
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(transactionTemplate.execute(anyCallback())).thenAnswer(invocation -> {
            transactionStartedAt.set(System.nanoTime());
            return new BookingOutcome(null, CAPACITY, 1);
        });

        // when
        strategy(DEBUG_HOLD).book(SESSION_ID, MEMBER_ID);

        // then
        assertThat(millis(transactionStartedAt.get() - lockedAt.get())).isGreaterThanOrEqualTo(DEBUG_HOLD.toMillis());
        InOrder order = inOrder(lock, transactionTemplate);
        order.verify(lock).tryLock(anyLong(), any(TimeUnit.class));
        order.verify(transactionTemplate).execute(anyCallback());
        order.verify(lock).unlock();
    }

    @Test
    void zeroDebugHoldStartsTheTransactionWithoutWaiting() throws InterruptedException {
        // given
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(transactionTemplate.execute(anyCallback())).thenReturn(new BookingOutcome(null, CAPACITY, 1));
        long startedAt = System.nanoTime();

        // when
        strategy(Duration.ZERO).book(SESSION_ID, MEMBER_ID);

        // then
        assertThat(millis(System.nanoTime() - startedAt)).isLessThan(DEBUG_HOLD.toMillis());
    }

    private DistributedLockBookingStrategy strategy(Duration debugHold) {
        BookingProperties properties = new BookingProperties(BookingConcurrencyMode.DISTRIBUTED_LOCK,
                new BookingProperties.OptimisticRetry(1, Duration.ZERO, Duration.ZERO),
                new BookingProperties.SoldOutCache(false, Duration.ZERO, 1),
                new BookingProperties.DistributedLock(WAIT_TIME, debugHold),
                new BookingProperties.JvmLock(Duration.ZERO));
        return new DistributedLockBookingStrategy(mock(CourtSessionService.class), mock(MemberService.class),
                mock(BookingRepository.class), transactionTemplate, redissonClient, properties);
    }

    private static TransactionCallback<BookingOutcome> anyCallback() {
        return any();
    }

    private static double millis(long nanos) {
        return nanos / NANOS_PER_MILLI;
    }
}
