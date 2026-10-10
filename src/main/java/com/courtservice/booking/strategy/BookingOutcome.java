package com.courtservice.booking.strategy;


import com.courtservice.booking.entity.Booking;

/**
 * Result of a booking strategy.
 * @param booking     the persisted booking
 * @param capacity    the session capacity at booking time
 * @param bookedCount the booked count this strategy wrote
 */
public record BookingOutcome(Booking booking, int capacity, int bookedCount) {
}
