package com.courtservice.booking.strategy;

/**
 * One way of creating a booking under concurrency. Implementations live in the {@code impl}
 * subpackage; exactly one is active, chosen by {@code app.booking.concurrency-mode} through
 * {@link BookingStrategyRegistry}.
 *
 * <p>Each implementation owns its transaction boundary, because some strategies (JVM locks,
 * optimistic retries) must hold a lock or retry outside the database transaction.
 */
public interface BookingStrategy {

    /**
     * Returns the mode this strategy implements.
     *
     * @return the concurrency mode handled by this strategy
     */
    BookingConcurrencyMode mode();

    /**
     * Books a court session for a member.
     *
     * @param courtSessionId the session to book
     * @param memberId       the member making the booking
     * @return the persisted booking with the capacity and booked count the strategy wrote
     * @throws com.courtservice.common.error.BusinessException if the session or member does not
     *                                                         exist, the session is full, or the
     *                                                         member already booked it
     */
    BookingOutcome book(Long courtSessionId, Long memberId);
}
