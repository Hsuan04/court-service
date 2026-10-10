package com.courtservice.booking.event;

/**
 * Published when a booking is cancelled, so that state kept outside the database can follow once
 * the cancellation has committed: the Redis seat stock gets the seat back and the member out of
 * its set, and the sold-out flag of the session is cleared.
 *
 * @param bookingId      the cancelled booking
 * @param courtSessionId the session whose seat was freed
 * @param memberId       the member who held the seat
 */
public record BookingCancelledEvent(Long bookingId, Long courtSessionId, Long memberId) {
}
