package com.courtservice.booking.concurrency;

import com.courtservice.booking.config.BookingProperties;
import com.courtservice.booking.service.BookingService;
import com.courtservice.booking.strategy.BookingConcurrencyMode;
import com.courtservice.booking.strategy.impl.OptimisticLockBookingStrategy;
import com.courtservice.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>Purpose:</b> prove that the optimistic-lock strategy ({@code @Version} check on the session,
 * retried in a new transaction after a conflict) never oversells and never loses an update under
 * contention.
 *
 * <p><b>Steps</b> (see {@link BookingRaceIntegrationTestBase}): create a court, create a session,
 * create the members, then let every member book at the same moment.
 *
 * <p><b>Expected:</b> a conflicting update is detected and never written, so the safety
 * invariants are asserted exactly: no more rows than seats, {@code booked_count} equal to the rows,
 * one {@code SUCCESS} per row, and every request answered with {@code SUCCESS},
 * {@code SESSION_FULL} or {@code CONCURRENT_MODIFICATION}. Filling every seat is <em>not</em>
 * guaranteed by design: a request that keeps losing races runs out of attempts and gives up with
 * {@code CONCURRENT_MODIFICATION} while seats may remain. The report shows how many did.
 *
 * <p>The strategy is built with fast retries (many attempts, millisecond backoff) instead of the
 * configured one-to-five-second backoff, so the race finishes in seconds.
 */
class OptimisticLockBookingRaceIntegrationTest extends BookingRaceIntegrationTestBase {

    private static final int MAX_ATTEMPTS = 30;
    private static final Duration MIN_BACKOFF = Duration.ofMillis(1);
    private static final Duration MAX_BACKOFF = Duration.ofMillis(20);

    @Test
    void conflictsAreRetriedAndNothingIsOversold() throws InterruptedException {
        // given: steps 1 to 3
        long sessionId = createSession(createCourt(), CAPACITY);
        List<Long> memberIds = createMembers(MEMBERS);
        BookingService bookingService = bookingServiceUsing(fastRetryStrategy());

        // when: step 4
        RaceResult result = race(everyMemberBooks(bookingService, sessionId, memberIds));

        // then
        logReport("OPTIMISTIC_LOCK: " + MEMBERS + " members, " + CAPACITY + " seats", result, sessionId, CAPACITY);
        int rows = bookingRows(sessionId);
        assertThat(rows).as("no oversell").isLessThanOrEqualTo(CAPACITY);
        assertThat(bookedCount(sessionId)).as("no lost update").isEqualTo(rows);
        assertThat(result.count(sessionId, SUCCESS)).as("one success per row").isEqualTo(rows);
        assertThat(result.count(sessionId, SUCCESS)
                + result.count(sessionId, ErrorCode.SESSION_FULL.name())
                + result.count(sessionId, ErrorCode.CONCURRENT_MODIFICATION.name()))
                .as("every request answered with an expected outcome")
                .isEqualTo(MEMBERS);
    }

    private OptimisticLockBookingStrategy fastRetryStrategy() {
        BookingProperties properties = new BookingProperties(BookingConcurrencyMode.OPTIMISTIC_LOCK,
                new BookingProperties.OptimisticRetry(MAX_ATTEMPTS, MIN_BACKOFF, MAX_BACKOFF),
                bookingProperties.soldOutCache(), bookingProperties.distributedLock(),
                bookingProperties.jvmLock());
        return new OptimisticLockBookingStrategy(courtSessionService, memberService, bookingRepository,
                transactionTemplate, properties);
    }
}
