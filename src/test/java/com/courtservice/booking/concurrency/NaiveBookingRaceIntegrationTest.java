package com.courtservice.booking.concurrency;

import com.courtservice.booking.service.BookingService;
import com.courtservice.booking.strategy.BookingConcurrencyMode;
import com.courtservice.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>Purpose:</b> the control group. The naive strategy reads {@code booked_count}, checks it and
 * writes back "read value + 1" with no concurrency control, to show what the other strategies
 * prevent: overselling (more booking rows than seats) and lost updates ({@code booked_count}
 * lower than the number of rows).
 *
 * <p><b>Steps</b> (see {@link BookingRaceIntegrationTestBase}): create a court, create a session,
 * create the members, then let every member book at the same moment.
 *
 * <p><b>Expected:</b> how much it oversells depends on thread timing, so the oversell itself is
 * only reported, never asserted. What always holds is asserted: every {@code SUCCESS} response
 * wrote a booking row, and every other response is a plain {@code SESSION_FULL}.
 */
class NaiveBookingRaceIntegrationTest extends BookingRaceIntegrationTestBase {

    @Test
    void unprotectedReadCheckWriteIsReportedForComparison() throws InterruptedException {
        // given: steps 1 to 3
        long sessionId = createSession(createCourt(), CAPACITY);
        List<Long> memberIds = createMembers(MEMBERS);
        BookingService bookingService = bookingServiceUsing(strategy(BookingConcurrencyMode.NAIVE));

        // when: step 4
        RaceResult result = race(everyMemberBooks(bookingService, sessionId, memberIds));

        // then: the report shows the oversell and lost updates of this run
        logReport("NAIVE (control group): " + MEMBERS + " members, " + CAPACITY + " seats", result, sessionId,
                CAPACITY);
        assertThat(result.outcomesOf(sessionId).keySet()).as("only success or session full")
                .isSubsetOf(Set.of(SUCCESS, ErrorCode.SESSION_FULL.name()));
        assertThat(result.count(sessionId, SUCCESS)).as("every success wrote a booking row")
                .isEqualTo(bookingRows(sessionId));
    }
}
