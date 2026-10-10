package com.courtservice.booking.stock;

import com.courtservice.booking.event.BookingCancelledEvent;
import com.courtservice.session.CourtSessionCapacityChangedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Keeps the Redis seat stock of a session in step with its capacity and with cancellations, for
 * both Redis booking strategies.
 *
 * <p>The difference is added to the stock instead of deleting the key and letting the next
 * booking reload it: a reload computes {@code capacity - bookedCount} from the database, which
 * does not yet count seats reserved in Redis whose booking has not committed, so those seats would
 * be handed out twice. Adding the difference keeps them reserved.
 *
 * <p>Runs after commit, so a rolled-back change never touches Redis. Until it runs, bookings still
 * see the old stock: after a raise they may be refused a seat that is already free, and after a
 * cut Redis may let through more requests than the new capacity allows, which the conditional
 * database update then rejects.
 */
@Slf4j
@Component
public class SeatStockSynchronizer {

    private final SeatStockScripts seatStockScripts;

    public SeatStockSynchronizer(SeatStockScripts seatStockScripts) {
        this.seatStockScripts = seatStockScripts;
    }

    /**
     * Adds a capacity change to the seat stock of the session, if the stock exists. A missing
     * stock is initialized later from the database, which already has the new capacity.
     *
     * <p>A Redis failure is logged and not rethrown: the capacity change is already committed and
     * must not be reported to the client as failed. The stock then stays off by the difference
     * until it is rebuilt, and the database still prevents any oversell.
     *
     * @param event the capacity change
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCapacityChanged(CourtSessionCapacityChangedEvent event) {
        try {
            if (seatStockScripts.adjust(event.courtSessionId(), event.capacityDelta())) {
                log.info("Session {}: seat stock adjusted by {} after a capacity change", event.courtSessionId(),
                        event.capacityDelta());
            }
        } catch (RuntimeException e) {
            log.error("Session {}: seat stock could not be adjusted by {} after a capacity change",
                    event.courtSessionId(), event.capacityDelta(), e);
        }
    }

    /**
     * Gives the seat of a cancelled booking back to the stock, if the stock exists, and removes the
     * member from the session's member set so that the member can book the session again.
     *
     * <p>Runs after commit, so a rolled-back cancellation never frees a seat in Redis. Until it
     * runs, the seat is free in the database but not yet in Redis, so a booking may briefly be
     * refused it. A Redis failure is logged and not rethrown, for the same reason as a capacity
     * change: the cancellation is already committed. The seat is then missing from the stock and
     * the member may be rejected as a duplicate until the stock is rebuilt.
     *
     * @param event the cancellation
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBookingCancelled(BookingCancelledEvent event) {
        try {
            seatStockScripts.release(event.courtSessionId(), event.memberId(), true);
            log.info("Session {}: seat of cancelled booking {} given back to the seat stock",
                    event.courtSessionId(), event.bookingId());
        } catch (RuntimeException e) {
            log.error("Session {}: seat of cancelled booking {} could not be given back to the seat stock",
                    event.courtSessionId(), event.bookingId(), e);
        }
    }
}
