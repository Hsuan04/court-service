package com.courtservice.booking.strategy.impl;

import com.courtservice.booking.entity.Booking;
import com.courtservice.booking.entity.BookingStatus;
import com.courtservice.booking.repository.BookingRepository;
import com.courtservice.booking.stock.SeatStockKeys;
import com.courtservice.booking.stock.SeatStockScripts;
import com.courtservice.booking.stock.SeatStockScripts.Reservation;
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
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Reserves a seat with one Lua script that checks the member, checks the stock, deducts the seat
 * and records the member as a single atomic step in Redis (see {@link SeatStockScripts}). Only
 * requests that got a seat go on to the database.
 *
 * <ul>
 *   <li>Unlike {@code redis-cache}, the stock never goes below zero and no seat has to be given
 *       back on rejection, because the check and the deduction cannot be separated.</li>
 *   <li>Repeated requests of the same member are rejected in Redis with {@code DUPLICATE_BOOKING},
 *       before they take a seat or reach the database. The database unique constraint stays as
 *       the safeguard, so no {@code exists} query is made in the transaction.</li>
 *   <li>The stock and member set ({@link SeatStockKeys}) are initialized lazily from the database
 *       by the first booking, in one script that keeps only the first of concurrent
 *       initializations. They have no TTL for the same reason as {@code redis-cache}.</li>
 *   <li>The database write is the same conditional update as {@code atomic-update}, so the
 *       database still prevents any oversell if Redis drifts from the truth.</li>
 *   <li>Redis is called outside the transaction. If the transaction fails, the reservation is
 *       undone after the rollback.</li>
 *   <li>Not crash-safe: an instance that dies between the reservation and the commit loses that
 *       seat and leaves the member in the set, and nothing reconciles them with the database.</li>
 * </ul>
 */
@Slf4j
@Component
public class RedisLuaBookingStrategy implements BookingStrategy {

    private final CourtSessionService courtSessionService;
    private final MemberService memberService;
    private final BookingRepository bookingRepository;
    private final TransactionTemplate transactionTemplate;
    private final SeatStockScripts seatStockScripts;

    public RedisLuaBookingStrategy(CourtSessionService courtSessionService,
                                   MemberService memberService,
                                   BookingRepository bookingRepository,
                                   TransactionTemplate transactionTemplate,
                                   SeatStockScripts seatStockScripts) {
        this.courtSessionService = courtSessionService;
        this.memberService = memberService;
        this.bookingRepository = bookingRepository;
        this.transactionTemplate = transactionTemplate;
        this.seatStockScripts = seatStockScripts;
    }

    @Override
    public BookingConcurrencyMode mode() {
        return BookingConcurrencyMode.REDIS_LUA;
    }

    /**
     * Reserves a seat in Redis, then writes the booking in a transaction, undoing the reservation
     * if the write fails.
     *
     * @throws BusinessException     {@code SESSION_FULL} if Redis or the database has no seat left,
     *                               {@code DUPLICATE_BOOKING} if the member already holds a seat,
     *                               or {@code RESOURCE_NOT_FOUND} if the session or member does not
     *                               exist
     * @throws IllegalStateException if the stock disappears right after it was initialized
     */
    @Override
    public BookingOutcome book(Long courtSessionId, Long memberId) {
        Reservation reservation = seatStockScripts.reserve(courtSessionId, memberId);
        if (reservation == Reservation.NOT_INITIALIZED) {
            initializeStock(courtSessionId);
            reservation = seatStockScripts.reserve(courtSessionId, memberId);
        }
        switch (reservation) {
            case RESERVED -> log.debug("Session {} member {}: seat reserved in Redis", courtSessionId, memberId);
            case NO_SEAT_LEFT -> {
                log.info("Session {} member {}: no seat left in the Redis stock", courtSessionId, memberId);
                throw new BusinessException(ErrorCode.SESSION_FULL, ErrorCode.SESSION_FULL.getDefaultDetail());
            }
            case ALREADY_RESERVED -> {
                log.info("Session {} member {}: member already holds a seat in Redis", courtSessionId, memberId);
                throw new BusinessException(ErrorCode.DUPLICATE_BOOKING,
                        ErrorCode.DUPLICATE_BOOKING.getDefaultDetail());
            }
            case NOT_INITIALIZED -> throw new IllegalStateException(
                    "Seat stock of session " + courtSessionId + " disappeared right after initialization");
        }
        try {
            return transactionTemplate.execute(status -> doBook(courtSessionId, memberId));
        } catch (BusinessException e) {
            // A full database means Redis granted a seat that does not exist, so the seat is not
            // given back; the member is removed so that a later attempt is not taken for a duplicate.
            release(courtSessionId, memberId, e.getErrorCode() != ErrorCode.SESSION_FULL, e);
            throw e;
        } catch (RuntimeException e) {
            release(courtSessionId, memberId, true, e);
            throw e;
        }
    }

    /**
     * Loads the remaining seats and the members who booked from the database, then initializes
     * Redis with them. The reads share one transaction; Redis is called after it ends.
     *
     * @throws com.courtservice.common.error.ResourceNotFoundException if the session does not exist
     */
    private void initializeStock(Long courtSessionId) {
        StockSnapshot snapshot = transactionTemplate.execute(status -> {
            CourtSession session = courtSessionService.findSession(courtSessionId);
            return new StockSnapshot(session.getCapacity() - session.getBookedCount(),
                    bookingRepository.findMemberIdsByCourtSessionIdAndStatus(courtSessionId,
                            BookingStatus.CONFIRMED));
        });
        if (seatStockScripts.initialize(courtSessionId, snapshot.remainingSeats(), snapshot.memberIds())) {
            log.info("Session {}: seat stock initialized with {} seats and {} members", courtSessionId,
                    snapshot.remainingSeats(), snapshot.memberIds().size());
        }
    }

    private BookingOutcome doBook(Long courtSessionId, Long memberId) {
        Member member = memberService.findMember(memberId);
        BookedCountSnapshot counters = courtSessionService.incrementBookedCountIfAvailable(courtSessionId)
                .orElseThrow(() -> fullOrMissing(courtSessionId, memberId));
        Booking booking = bookingRepository.save(
                new Booking(courtSessionService.getSessionReference(courtSessionId), member));
        return new BookingOutcome(booking, counters.getCapacity(), counters.getBookedCount());
    }

    /**
     * Explains a conditional update that matched no row: the session was deleted, or Redis
     * granted a seat the database does not have.
     *
     * @throws com.courtservice.common.error.ResourceNotFoundException if the session does not exist
     */
    private BusinessException fullOrMissing(Long courtSessionId, Long memberId) {
        courtSessionService.verifyExists(courtSessionId);
        log.warn("Session {} member {}: Redis reserved a seat but the database is full", courtSessionId, memberId);
        return new BusinessException(ErrorCode.SESSION_FULL, ErrorCode.SESSION_FULL.getDefaultDetail());
    }

    /** A failure to undo the reservation must not hide why the booking failed, so it is only logged. */
    private void release(Long courtSessionId, Long memberId, boolean returnSeat, RuntimeException cause) {
        try {
            boolean seatReturned = seatStockScripts.release(courtSessionId, memberId, returnSeat);
            log.info("Session {} member {}: Redis reservation undone after a failed booking (seat returned: {})",
                    courtSessionId, memberId, seatReturned);
        } catch (RuntimeException e) {
            cause.addSuppressed(e);
            log.error("Session {} member {}: Redis reservation could not be undone", courtSessionId, memberId, e);
        }
    }

    /** What the database says the Redis stock should start from. */
    private record StockSnapshot(int remainingSeats, List<Long> memberIds) {
    }
}
