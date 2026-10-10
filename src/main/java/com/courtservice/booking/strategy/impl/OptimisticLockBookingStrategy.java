package com.courtservice.booking.strategy.impl;

import com.courtservice.booking.entity.Booking;
import com.courtservice.booking.entity.BookingStatus;
import com.courtservice.booking.repository.BookingRepository;
import com.courtservice.booking.strategy.BookingConcurrencyMode;
import com.courtservice.booking.strategy.BookingOutcome;
import com.courtservice.booking.config.BookingProperties;
import com.courtservice.booking.strategy.BookingStrategy;
import com.courtservice.common.error.BusinessException;
import com.courtservice.common.error.ErrorCode;
import com.courtservice.member.Member;
import com.courtservice.member.MemberService;
import com.courtservice.session.CourtSession;
import com.courtservice.session.CourtSessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Optimistic locking: reads the session without a lock and relies on its {@code @Version} column
 * to detect that another booking changed it first, then retries in a new transaction.
 *
 * <p>Each attempt runs in its own transaction through {@link TransactionTemplate}, so a retry
 * starts with an empty persistence context and re-reads the current {@code bookedCount} and
 * {@code version}. This relies on {@code BookingService#create} running without a surrounding
 * transaction; inside one, every retry would reuse the stale entity and conflict again.
 *
 * <p>The seat is written with an immediate flush before the booking is inserted, so a conflict is
 * detected before any booking row is written.
 *
 * <p>Retries wait a random time within {@code app.booking.optimistic-retry.min-backoff} and
 * {@code max-backoff} (1 to 5 seconds by default), so requests that conflicted together spread out
 * instead of colliding again on the same row. The wait happens between transactions and holds no
 * database connection, but it does hold the request thread and adds to the response time: in the
 * worst case a request waits {@code (maxAttempts - 1) * maxBackoff}.
 *
 * <p>Trade-off: under heavy contention on one session a request can run out of attempts. Before
 * giving up it re-reads the session, so a request that lost only because the session filled up is
 * answered with {@code SESSION_FULL} rather than a retry hint.
 */
@Slf4j
@Component
public class OptimisticLockBookingStrategy implements BookingStrategy {

    private final CourtSessionService courtSessionService;
    private final MemberService memberService;
    private final BookingRepository bookingRepository;
    private final TransactionTemplate transactionTemplate;
    private final BookingProperties.OptimisticRetry retry;

    public OptimisticLockBookingStrategy(CourtSessionService courtSessionService,
                                         MemberService memberService,
                                         BookingRepository bookingRepository,
                                         TransactionTemplate transactionTemplate,
                                         BookingProperties properties) {
        this.courtSessionService = courtSessionService;
        this.memberService = memberService;
        this.bookingRepository = bookingRepository;
        this.transactionTemplate = transactionTemplate;
        this.retry = properties.optimisticRetry();
    }

    @Override
    public BookingConcurrencyMode mode() {
        return BookingConcurrencyMode.OPTIMISTIC_LOCK;
    }

    /**
     * Books a seat, retrying up to {@code maxAttempts} times in total when another transaction
     * changed the session first. Business rule violations are never retried.
     *
     * @throws BusinessException
     */
    @Override
    public BookingOutcome book(Long courtSessionId, Long memberId) {
        for (int attempt = 1; ; attempt++) {
            int currentAttempt = attempt;
            try {
                return transactionTemplate.execute(status -> doBook(courtSessionId, memberId, currentAttempt));
            } catch (OptimisticLockingFailureException e) {
                if (attempt >= retry.maxAttempts()) {
                    throw giveUp(courtSessionId, memberId);
                }
                long waitMs = randomBackoffMillis();
                log.info("Session {} member {}: version conflict on attempt {}/{}, waiting {} ms before attempt {}",
                        courtSessionId, memberId, attempt, retry.maxAttempts(), waitMs, attempt + 1);
                sleep(waitMs);
            }
        }
    }

    private BookingOutcome doBook(Long courtSessionId, Long memberId, int attempt) {
        log.debug("Session {} member {}: booking attempt {}/{}", courtSessionId, memberId, attempt,
                retry.maxAttempts());

        CourtSession session = courtSessionService.findSession(courtSessionId);
        Member member = memberService.findMember(memberId);

        // A racing request of the same member can also pass this check, but both then update the
        // same session row, so the later one hits a version conflict and finds the booking on retry.
        if (bookingRepository.existsByCourtSessionIdAndMemberIdAndStatus(courtSessionId, memberId,
                BookingStatus.CONFIRMED)) {
            throw new BusinessException(ErrorCode.DUPLICATE_BOOKING, ErrorCode.DUPLICATE_BOOKING.getDefaultDetail());
        }

        // Written first so that a version conflict aborts the attempt before the booking insert.
        courtSessionService.bookSeat(session);
        Booking booking = bookingRepository.save(new Booking(session, member));

        if (attempt > 1) {
            log.info("Session {} member {}: booked on attempt {}/{}", courtSessionId, memberId, attempt,
                    retry.maxAttempts());
        }
        return new BookingOutcome(booking, session.getCapacity(), session.getBookedCount());
    }

    /**
     * Decides the answer once every attempt has conflicted. The re-read tells a request that lost
     * because the session filled up apart from one that kept losing races while seats were free.
     */
    private BusinessException giveUp(Long courtSessionId, Long memberId) {
        CourtSession session = courtSessionService.findSession(courtSessionId);
        if (session.isFull()) {
            log.info("Session {} member {}: gave up after {} version conflicts; the session is full",
                    courtSessionId, memberId, retry.maxAttempts());
            return new BusinessException(ErrorCode.SESSION_FULL, ErrorCode.SESSION_FULL.getDefaultDetail());
        }
        log.warn("Session {} member {}: gave up after {} version conflicts with {} of {} seats taken",
                courtSessionId, memberId, retry.maxAttempts(), session.getBookedCount(), session.getCapacity());
        return new BusinessException(ErrorCode.CONCURRENT_MODIFICATION,
                ErrorCode.CONCURRENT_MODIFICATION.getDefaultDetail());
    }

    /** Draws a wait uniformly from {@code [minBackoff, maxBackoff]}, both inclusive. */
    private long randomBackoffMillis() {
        return ThreadLocalRandom.current().nextLong(retry.minBackoff().toMillis(), retry.maxBackoff().toMillis() + 1);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry", e);
        }
    }
}
