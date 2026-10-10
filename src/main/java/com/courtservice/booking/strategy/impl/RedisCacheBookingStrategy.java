package com.courtservice.booking.strategy.impl;

import com.courtservice.booking.entity.Booking;
import com.courtservice.booking.entity.BookingStatus;
import com.courtservice.booking.repository.BookingRepository;
import com.courtservice.booking.stock.SeatStockKeys;
import com.courtservice.booking.strategy.BookingConcurrencyMode;
import com.courtservice.booking.strategy.BookingOutcome;
import com.courtservice.booking.strategy.BookingStrategy;
import com.courtservice.common.error.BusinessException;
import com.courtservice.common.error.ErrorCode;
import com.courtservice.member.Member;
import com.courtservice.member.MemberService;
import com.courtservice.session.BookedCountSnapshot;
import com.courtservice.session.CourtSession;
import com.courtservice.session.CourtSessionService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pre-deducts a seat from a counter in Redis ({@link SeatStockKeys#stock(Long)}) with
 * {@code DECR}, and only requests that got a seat go on to the database. Once the counter reaches
 * zero, every further request is rejected by Redis alone, so the database sees about
 * {@code capacity} writes however many requests arrive.
 *
 * <ul>
 *   <li>{@code DECR} is atomic, but the check of its result is a separate step: a request that
 *       drives the counter below zero has already taken a seat it may not have, and must give it
 *       back with {@code INCR}. The counter is briefly negative while that happens; it is never
 *       used to hand out a seat, because a seat is granted only when {@code DECR} returns zero or
 *       more. Doing the check and the deduction in one step is what {@code redis-lua} adds.</li>
 *   <li>The counter is initialized lazily from the database by the first booking, with
 *       {@code SET NX} so that concurrent initializations keep only the first value. It has no
 *       TTL: an expired counter would be reloaded from {@code booked_count}, which does not count
 *       seats reserved in Redis but not yet committed. Capacity changes are applied by
 *       {@link com.courtservice.booking.stock.SeatStockSynchronizer}.</li>
 *   <li>The database write is the same conditional update as {@code atomic-update}, so the
 *       database still prevents any oversell if the counter drifts from the truth.</li>
 *   <li>Redis is called outside the transaction, so a request waiting on Redis holds no database
 *       connection. If the transaction fails, the seat is given back after the rollback.</li>
 *   <li>Not crash-safe: an instance that dies between {@code DECR} and the commit loses that seat
 *       from the counter, and nothing reconciles it with the database.</li>
 * </ul>
 */
@Slf4j
@Component
public class RedisCacheBookingStrategy implements BookingStrategy {

    private final CourtSessionService courtSessionService;
    private final MemberService memberService;
    private final BookingRepository bookingRepository;
    private final TransactionTemplate transactionTemplate;
    private final RedissonClient redissonClient;

    public RedisCacheBookingStrategy(CourtSessionService courtSessionService,
                                     MemberService memberService,
                                     BookingRepository bookingRepository,
                                     TransactionTemplate transactionTemplate,
                                     RedissonClient redissonClient) {
        this.courtSessionService = courtSessionService;
        this.memberService = memberService;
        this.bookingRepository = bookingRepository;
        this.transactionTemplate = transactionTemplate;
        this.redissonClient = redissonClient;
    }

    @Override
    public BookingConcurrencyMode mode() {
        return BookingConcurrencyMode.REDIS_CACHE;
    }

    /**
     * Takes a seat in Redis, then writes the booking in a transaction, giving the seat back if the
     * write fails for any reason other than the database finding the session full.
     *
     * @throws BusinessException {@code SESSION_FULL} if Redis or the database has no seat left,
     *                           {@code RESOURCE_NOT_FOUND} if the session or member does not exist,
     *                           or {@code DUPLICATE_BOOKING} if the member already booked the session
     */
    @Override
    public BookingOutcome book(Long courtSessionId, Long memberId) {
        RAtomicLong stock = ensureStock(courtSessionId);
        if (stock.decrementAndGet() < 0) {
            stock.incrementAndGet();
            log.info("Session {} member {}: no seat left in the Redis stock", courtSessionId, memberId);
            throw new BusinessException(ErrorCode.SESSION_FULL, ErrorCode.SESSION_FULL.getDefaultDetail());
        }
        try {
            return transactionTemplate.execute(status -> doBook(courtSessionId, memberId));
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.SESSION_FULL) {
                // Redis granted a seat the database does not have, so the counter is too high;
                // giving the seat back would widen that gap.
                log.warn("Session {} member {}: Redis stock granted a seat but the database is full",
                        courtSessionId, memberId);
            } else {
                giveSeatBack(stock, courtSessionId, memberId, e);
            }
            throw e;
        } catch (RuntimeException e) {
            giveSeatBack(stock, courtSessionId, memberId, e);
            throw e;
        }
    }

    /**
     * Returns the session's counter, initializing it from the database if it does not exist yet.
     *
     * @throws com.courtservice.common.error.ResourceNotFoundException if the session does not exist
     */
    private RAtomicLong ensureStock(Long courtSessionId) {
        String key = SeatStockKeys.stock(courtSessionId);
        RBucket<String> bucket = redissonClient.getBucket(key, StringCodec.INSTANCE);
        if (!bucket.isExists()) {
            CourtSession session = courtSessionService.findSession(courtSessionId);
            int remainingSeats = session.getCapacity() - session.getBookedCount();
            if (bucket.setIfAbsent(Integer.toString(remainingSeats))) {
                log.info("Session {}: seat stock initialized with {} seats", courtSessionId, remainingSeats);
            }
        }
        return redissonClient.getAtomicLong(key);
    }

    private BookingOutcome doBook(Long courtSessionId, Long memberId) {
        Member member = memberService.findMember(memberId);
        if (bookingRepository.existsByCourtSessionIdAndMemberIdAndStatus(courtSessionId, memberId,
                BookingStatus.CONFIRMED)) {
            throw new BusinessException(ErrorCode.DUPLICATE_BOOKING, ErrorCode.DUPLICATE_BOOKING.getDefaultDetail());
        }
        BookedCountSnapshot counters = courtSessionService.incrementBookedCountIfAvailable(courtSessionId)
                .orElseThrow(() -> fullOrMissing(courtSessionId));
        Booking booking = bookingRepository.save(
                new Booking(courtSessionService.getSessionReference(courtSessionId), member));
        return new BookingOutcome(booking, counters.getCapacity(), counters.getBookedCount());
    }

    /**
     * Explains a conditional update that matched no row: the session filled up or was deleted.
     *
     * @throws com.courtservice.common.error.ResourceNotFoundException if the session does not exist
     */
    private BusinessException fullOrMissing(Long courtSessionId) {
        courtSessionService.verifyExists(courtSessionId);
        return new BusinessException(ErrorCode.SESSION_FULL, ErrorCode.SESSION_FULL.getDefaultDetail());
    }

    /** A failure to give the seat back must not hide why the booking failed, so it is only logged. */
    private static void giveSeatBack(RAtomicLong stock, Long courtSessionId, Long memberId, RuntimeException cause) {
        try {
            stock.incrementAndGet();
            log.info("Session {} member {}: seat given back to the Redis stock after a failed booking",
                    courtSessionId, memberId);
        } catch (RuntimeException e) {
            cause.addSuppressed(e);
            log.error("Session {} member {}: seat could not be given back to the Redis stock",
                    courtSessionId, memberId, e);
        }
    }
}
