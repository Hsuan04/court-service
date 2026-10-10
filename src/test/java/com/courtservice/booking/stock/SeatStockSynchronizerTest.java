package com.courtservice.booking.stock;

import com.courtservice.booking.event.BookingCancelledEvent;
import com.courtservice.session.CourtSessionCapacityChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SeatStockSynchronizerTest {

    private static final long SESSION_ID = 1L;
    private static final int CAPACITY_DELTA = 2;
    private static final long BOOKING_ID = 3L;
    private static final long MEMBER_ID = 4L;
    private static final BookingCancelledEvent CANCELLED = new BookingCancelledEvent(BOOKING_ID, SESSION_ID, MEMBER_ID);

    private SeatStockScripts scripts;
    private SeatStockSynchronizer synchronizer;

    @BeforeEach
    void setUp() {
        scripts = mock(SeatStockScripts.class);
        synchronizer = new SeatStockSynchronizer(scripts);
    }

    @Test
    void capacityChangeIsAddedToTheStock() {
        // when
        synchronizer.onCapacityChanged(new CourtSessionCapacityChangedEvent(SESSION_ID, CAPACITY_DELTA));

        // then
        verify(scripts).adjust(SESSION_ID, CAPACITY_DELTA);
    }

    /** The capacity change is already committed, so a Redis failure must not fail the update request. */
    @Test
    void redisFailureIsNotPropagated() {
        // given
        when(scripts.adjust(SESSION_ID, CAPACITY_DELTA)).thenThrow(new IllegalStateException("Redis unavailable"));

        // when / then
        assertThatCode(() -> synchronizer.onCapacityChanged(
                new CourtSessionCapacityChangedEvent(SESSION_ID, CAPACITY_DELTA))).doesNotThrowAnyException();
    }

    @Test
    void cancellationGivesTheSeatBackAndRemovesTheMember() {
        // when
        synchronizer.onBookingCancelled(CANCELLED);

        // then
        verify(scripts).release(SESSION_ID, MEMBER_ID, true);
    }

    /** The cancellation is already committed, so a Redis failure must not fail the cancel request. */
    @Test
    void redisFailureAfterACancellationIsNotPropagated() {
        // given
        when(scripts.release(SESSION_ID, MEMBER_ID, true)).thenThrow(new IllegalStateException("Redis unavailable"));

        // when / then
        assertThatCode(() -> synchronizer.onBookingCancelled(CANCELLED)).doesNotThrowAnyException();
    }
}
