package com.courtservice.booking.strategy.impl;

import com.courtservice.booking.config.BookingProperties;
import com.courtservice.booking.entity.Booking;
import com.courtservice.booking.entity.BookingStatus;
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
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One {@link ReentrantLock} per session in this JVM's memory: only bookings of the same session
 * queue, unlike {@code synchronized}, where every booking of every session waits on one lock.
 *
 * <ul>
 *   <li>The lock wraps the whole transaction, commit included, and is taken before the transaction
 *       starts, so waiters hold no database connection.</li>
 *   <li>Locks live in a Caffeine cache with weak values. A thread that holds or waits for a lock
 *       keeps a strong reference to it, so a lock in use can never be collected and replaced by a
 *       second lock for the same session; a lock nobody uses is reclaimed by the garbage
 *       collector, so the cache does not grow with every session ever booked.</li>
 *   <li>A waiter gives up after {@code app.booking.jvm-lock.wait-time} with {@code LOCK_TIMEOUT}.
 *       The lock is not fair, so waiters may be served out of arrival order.</li>
 *   <li>Single instance only: two instances have separate locks. Their bookings of one session then
 *       race on the row, and the {@code @Version} check rejects the loser with
 *       {@code CONCURRENT_MODIFICATION} instead of overselling. Use {@code distributed-lock} when
 *       running several instances.</li>
 *   <li>{@code ReentrantLock} does not pin a virtual thread while it waits, unlike
 *       {@code synchronized} on Java 21.</li>
 * </ul>
 */
@Slf4j
@Component
public class JvmLockBookingStrategy implements BookingStrategy {

    private final Cache<Long, ReentrantLock> locks = Caffeine.newBuilder().weakValues().build();

    private final CourtSessionService courtSessionService;
    private final MemberService memberService;
    private final BookingRepository bookingRepository;
    private final TransactionTemplate transactionTemplate;
    private final Duration waitTime;

    public JvmLockBookingStrategy(CourtSessionService courtSessionService,
                                  MemberService memberService,
                                  BookingRepository bookingRepository,
                                  TransactionTemplate transactionTemplate,
                                  BookingProperties properties) {
        this.courtSessionService = courtSessionService;
        this.memberService = memberService;
        this.bookingRepository = bookingRepository;
        this.transactionTemplate = transactionTemplate;
        this.waitTime = properties.jvmLock().waitTime();
    }

    @Override
    public BookingConcurrencyMode mode() {
        return BookingConcurrencyMode.JVM_LOCK;
    }

    /**
     * Locks the session, runs the transaction, then unlocks after commit or rollback.
     *
     * @throws BusinessException     {@code LOCK_TIMEOUT} if the lock is not acquired within the wait time
     * @throws IllegalStateException if interrupted while waiting
     */
    @Override
    public BookingOutcome book(Long courtSessionId, Long memberId) {
        ReentrantLock lock = lockFor(courtSessionId);
        if (!tryLock(lock)) {
            log.info("Session {} member {}: lock not acquired within {} ms", courtSessionId, memberId,
                    waitTime.toMillis());
            throw new BusinessException(ErrorCode.LOCK_TIMEOUT, ErrorCode.LOCK_TIMEOUT.getDefaultDetail());
        }
        try {
            return transactionTemplate.execute(status -> doBook(courtSessionId, memberId));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the lock of a session, creating it on first use. Package-private so tests can hold a
     * session's lock from another thread.
     *
     * @param courtSessionId the session id
     * @return the one lock of that session while anyone references it
     */
    ReentrantLock lockFor(Long courtSessionId) {
        return locks.get(courtSessionId, id -> new ReentrantLock());
    }

    private boolean tryLock(ReentrantLock lock) {
        try {
            return lock.tryLock(waitTime.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the booking lock", e);
        }
    }

    private BookingOutcome doBook(Long courtSessionId, Long memberId) {
        // A plain read is enough: no other booking of this session in this JVM runs until this one commits.
        CourtSession session = courtSessionService.findSession(courtSessionId);
        Member member = memberService.findMember(memberId);

        if (bookingRepository.existsByCourtSessionIdAndMemberIdAndStatus(courtSessionId, memberId,
                BookingStatus.CONFIRMED)) {
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
}
