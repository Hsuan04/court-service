package com.courtservice.booking.strategy.impl;

import com.courtservice.booking.config.BookingProperties;
import com.courtservice.booking.entity.Booking;
import com.courtservice.booking.lock.BookingLockKeys;
import com.courtservice.booking.repository.BookingRepository;
import com.courtservice.booking.strategy.BookingConcurrencyMode;
import com.courtservice.booking.strategy.BookingOutcome;
import com.courtservice.booking.strategy.BookingStrategy;
import com.courtservice.common.error.BusinessException;
import com.courtservice.common.error.ErrorCode;
import com.courtservice.member.Member;
import com.courtservice.member.MemberService;
import com.courtservice.session.CourtSession;
import com.courtservice.session.CourtSessionService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * One Redisson lock per session in Redis ({@code lock:court-session:{id}}), shared by every instance.
 *
 * <ul>
 *   <li>The lock wraps the whole transaction, commit included, and is taken before the transaction
 *       starts, so waiters hold no DB connection.</li>
 *   <li>No lease time: the watchdog keeps the lock alive while the holder runs.</li>
 *   <li>Not fair: waiters may get {@code LOCK_TIMEOUT} out of order. Use {@code getFairLock} or a
 *       queue if order matters.</li>
 *   <li>Not absolute: failover or a long GC pause can break mutual exclusion, so DB constraints remain
 *       the last line of defense.</li>
 * </ul>
 */
@Slf4j
@Component
public class DistributedLockBookingStrategy implements BookingStrategy {

    private static final double NANOS_PER_MILLI = 1_000_000.0;

    private final CourtSessionService courtSessionService;
    private final MemberService memberService;
    private final BookingRepository bookingRepository;
    private final TransactionTemplate transactionTemplate;
    private final RedissonClient redissonClient;
    private final Duration waitTime;
    private final Duration debugHold;

    public DistributedLockBookingStrategy(CourtSessionService courtSessionService,
                                          MemberService memberService,
                                          BookingRepository bookingRepository,
                                          TransactionTemplate transactionTemplate,
                                          RedissonClient redissonClient,
                                          BookingProperties properties) {
        this.courtSessionService = courtSessionService;
        this.memberService = memberService;
        this.bookingRepository = bookingRepository;
        this.transactionTemplate = transactionTemplate;
        this.redissonClient = redissonClient;
        this.waitTime = properties.distributedLock().waitTime();
        this.debugHold = properties.distributedLock().debugHold();
    }

    @Override
    public BookingConcurrencyMode mode() {
        return BookingConcurrencyMode.DISTRIBUTED_LOCK;
    }

    /**
     * Lock, run the transaction, then unlock after commit or rollback.
     *
     * @throws BusinessException     {@code LOCK_TIMEOUT} if the lock is not acquired within the wait time
     * @throws IllegalStateException if interrupted while waiting
     */
    @Override
    public BookingOutcome book(Long courtSessionId, Long memberId) {
        RLock lock = redissonClient.getLock(BookingLockKeys.forSession(courtSessionId));
        long waitStartedAt = System.nanoTime();
        if (!tryLock(lock)) {
            log.info("Session {} member {}: lock not acquired within {} ms", courtSessionId, memberId,
                    waitTime.toMillis());
            throw new BusinessException(ErrorCode.LOCK_TIMEOUT, ErrorCode.LOCK_TIMEOUT.getDefaultDetail());
        }
        long acquiredAt = System.nanoTime();
        log.debug("Session {} member {}: lock acquired after {} ms", courtSessionId, memberId,
                millisBetween(waitStartedAt, acquiredAt));
        try {
            holdForObservation(courtSessionId);
            return transactionTemplate.execute(status -> doBook(courtSessionId, memberId));
        } finally {
            releaseIfHeld(lock, courtSessionId, memberId, acquiredAt);
        }
    }

    private boolean tryLock(RLock lock) {
        try {
            return lock.tryLock(waitTime.toMillis(), TimeUnit.MILLISECONDS);   // No lease time -> watchdog
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the booking lock", e);
        }
    }

    /** Local observation only: keeps the lock before the transaction, so no DB connection is held. */
    private void holdForObservation(Long courtSessionId) {
        if (debugHold.isZero()) {
            return;
        }
        log.warn("Session {}: debug-hold active, keeping the booking lock for {} ms before booking",
                courtSessionId, debugHold.toMillis());
        try {
            Thread.sleep(debugHold);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during debug-hold", e);
        }
    }

    /** Unlocks only if still held; unlocking an expired lock would throw and hide the real exception. */
    private static void releaseIfHeld(RLock lock, Long courtSessionId, Long memberId, long acquiredAt) {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
            log.debug("Session {} member {}: lock released after holding {} ms", courtSessionId, memberId,
                    millisBetween(acquiredAt, System.nanoTime()));
        } else {
            log.warn("Session {} member {}: lock was no longer held at release; mutual exclusion may have "
                    + "been broken", courtSessionId, memberId);
        }
    }

    private BookingOutcome doBook(Long courtSessionId, Long memberId) {
        // Plain read is enough: the lock already serializes this session.
        CourtSession session = courtSessionService.findSession(courtSessionId);
        Member member = memberService.findMember(memberId);

        if (bookingRepository.existsByCourtSessionIdAndMemberId(courtSessionId, memberId)) {
            throw new BusinessException(ErrorCode.DUPLICATE_BOOKING, ErrorCode.DUPLICATE_BOOKING.getDefaultDetail());
        }
        if (session.isFull()) {
            log.info("Session {} member {}: session full ({} of {} seats taken)", courtSessionId, memberId,
                    session.getBookedCount(), session.getCapacity());
            throw new BusinessException(ErrorCode.SESSION_FULL, ErrorCode.SESSION_FULL.getDefaultDetail());
        }
        session.book();   // Flushed on commit, still inside the lock
        Booking booking = bookingRepository.save(new Booking(session, member));

        return new BookingOutcome(booking, session.getCapacity(), session.getBookedCount());
    }

    private static long millisBetween(long startNanos, long endNanos) {
        return Math.round((endNanos - startNanos) / NANOS_PER_MILLI);
    }
}