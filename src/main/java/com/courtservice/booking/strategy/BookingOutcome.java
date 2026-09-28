package com.courtservice.booking.strategy;


import com.courtservice.booking.Booking;

/**
 * Result of a booking strategy.
 * <p>
 * Carries the counters the strategy actually wrote, because the
 * {@code CourtSession} held in the persistence context is not refreshed by
 * bulk updates and may show a stale {@code bookedCount}.
 *
 * @param booking     the persisted booking
 * @param capacity    the session capacity at booking time
 * @param bookedCount the booked count this strategy wrote
 */
public record BookingOutcome(Booking booking, int capacity, int bookedCount) {
}
