package com.courtservice.session;

/**
 * The seat counters of a session as written by
 * {@link CourtSessionService#incrementBookedCountIfAvailable(Long)}, read back from the same
 * {@code UPDATE ... RETURNING} statement so no second query is needed.
 */
public interface BookedCountSnapshot {

    /**
     * Returns the booked count after the increment.
     *
     * @return the number of seats taken, including the one just booked
     */
    int getBookedCount();

    /**
     * Returns the capacity of the session.
     *
     * @return the total number of seats
     */
    int getCapacity();
}
