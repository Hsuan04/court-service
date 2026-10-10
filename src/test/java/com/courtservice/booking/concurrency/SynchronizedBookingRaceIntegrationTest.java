package com.courtservice.booking.concurrency;

import com.courtservice.booking.service.BookingService;
import com.courtservice.booking.strategy.BookingConcurrencyMode;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;
import java.util.stream.Stream;

/**
 * <b>Purpose:</b> prove that the synchronized strategy (one JVM-wide lock around the whole
 * transaction) never oversells, and show its cost: every booking of every session queues on the
 * same lock.
 *
 * <p><b>Steps</b> (see {@link BookingRaceIntegrationTestBase}): create a court, create the
 * session(s), create the members, then let every member book at the same moment.
 *
 * <p><b>Expected:</b> the lock serializes all bookings, so the outcome is exact. The two-session
 * case also logs, per thread, how long each request waited for the lock; requests of one session
 * wait behind requests of the other although the sessions are unrelated. Timings are only
 * logged, never asserted, because they depend on the machine.
 */
@Slf4j
class SynchronizedBookingRaceIntegrationTest extends BookingRaceIntegrationTestBase {

    private static final double NANOS_PER_MILLI = 1_000_000.0;
    private static final double P50 = 0.50;
    private static final double P95 = 0.95;
    private static final double MAX = 1.0;
    private static final String ROW_HEADER_FORMAT = "%5s | %-18s | %7s | %6s | %-12s | %10s | %9s | %10s";
    private static final String ROW_FORMAT = "%5d | %-18s | %7s | %6d | %-12s | %10.2f | %9.2f | %10.2f";

    private BookingService bookingService;

    @BeforeEach
    void useStrategy() {
        bookingService = bookingServiceUsing(strategy(BookingConcurrencyMode.SYNCHRONIZED));
    }

    /** 500 members race for 10 seats: exactly 10 win, 490 are told the session is full. */
    @Test
    void exactlyCapacityMembersWinTheRace() throws InterruptedException {
        // given: steps 1 to 3
        long sessionId = createSession(createCourt(), CAPACITY);
        List<Long> memberIds = createMembers(MEMBERS);

        // when: step 4
        RaceResult result = race(everyMemberBooks(bookingService, sessionId, memberIds));

        // then
        logReport("SYNCHRONIZED: " + MEMBERS + " members, " + CAPACITY + " seats", result, sessionId, CAPACITY);
        assertFilledExactly(result, sessionId, MEMBERS, CAPACITY);
    }

    /**
     * 500 members race for session 1 while another 500 race for the unrelated session 2. Both
     * sessions still fill exactly, and the wait table shows all 1000 bookings queuing on one lock.
     */
    @Test
    void twoUnrelatedSessionsFillExactlyWhileQueuingOnOneLock() throws InterruptedException {
        // given: steps 1 to 3, with two sessions and two groups of members
        long courtId = createCourt();
        long sessionOne = createSession(courtId, CAPACITY);
        long sessionTwo = createSession(courtId, CAPACITY);
        List<Long> groupOne = createMembers(MEMBERS);
        List<Long> groupTwo = createMembers(MEMBERS);

        // when: step 4, both groups at once
        RaceResult result = race(Stream.concat(
                everyMemberBooks(bookingService, sessionOne, groupOne).stream(),
                everyMemberBooks(bookingService, sessionTwo, groupTwo).stream()).toList());

        // then
        logWaitTable(result, sessionOne, sessionTwo);
        assertFilledExactly(result, sessionOne, MEMBERS, CAPACITY);
        assertFilledExactly(result, sessionTwo, MEMBERS, CAPACITY);
    }

    /**
     * Logs one row per request in lock order, then a summary per session. The lock serializes every
     * booking, so a request acquires it approximately when the request completing just before it
     * finishes (or when it started, if later); the lock time held so far by the other session's
     * requests is the part of its wait caused purely by sharing one lock with an unrelated session.
     */
    private static void logWaitTable(RaceResult result, long sessionOne, long sessionTwo) {
        List<Attempt> inLockOrder = result.attempts().stream()
                .sorted(Comparator.comparingLong(Attempt::endNanos))
                .toList();
        Map<Long, String> sessionLabels = Map.of(sessionOne, "1", sessionTwo, "2");
        Map<Long, Long> heldBySession = new HashMap<>(Map.of(sessionOne, 0L, sessionTwo, 0L));
        List<Timing> timings = new ArrayList<>(inLockOrder.size());

        log.info("=========== SYNCHRONIZED: per-thread lock wait (lock order) ===========");
        log.info(ROW_HEADER_FORMAT.formatted("#", "thread", "session", "member", "result",
                "wait ms", "hold ms", "total ms"));
        long previousEnd = result.gateOpenedAt();
        for (int i = 0; i < inLockOrder.size(); i++) {
            Attempt attempt = inLockOrder.get(i);
            long acquiredAt = Math.max(attempt.startNanos(), previousEnd);
            long otherSession = attempt.sessionId() == sessionOne ? sessionTwo : sessionOne;
            long wait = acquiredAt - attempt.startNanos();
            long hold = attempt.endNanos() - acquiredAt;
            Timing timing = new Timing(attempt.sessionId(), wait, Math.min(wait, heldBySession.get(otherSession)),
                    attempt.endNanos() - attempt.startNanos());
            timings.add(timing);
            heldBySession.merge(attempt.sessionId(), hold, Long::sum);
            previousEnd = attempt.endNanos();
            log.info(ROW_FORMAT.formatted(i + 1, attempt.thread(), sessionLabels.get(attempt.sessionId()),
                    attempt.memberId(), attempt.result(), millis(wait), millis(hold), millis(timing.total())));
        }

        log.info("=========== SYNCHRONIZED: summary ===========");
        log.info("Requests: {}   Capacity per session: {}   Elapsed: {} ms", inLockOrder.size(), CAPACITY,
                "%.2f".formatted(millis(previousEnd - result.gateOpenedAt())));
        for (long sessionId : List.of(sessionOne, sessionTwo)) {
            List<Timing> ofSession = timings.stream().filter(t -> t.sessionId() == sessionId).toList();
            log.info("Session {}: lock held {} ms in total | wait avg {} ms, p50 {} ms, p95 {} ms, max {} ms"
                            + " | waited behind the other session avg {} ms",
                    sessionLabels.get(sessionId), "%.2f".formatted(millis(heldBySession.get(sessionId))),
                    "%.2f".formatted(average(ofSession, Timing::lockWait)),
                    "%.2f".formatted(percentile(ofSession, Timing::lockWait, P50)),
                    "%.2f".formatted(percentile(ofSession, Timing::lockWait, P95)),
                    "%.2f".formatted(percentile(ofSession, Timing::lockWait, MAX)),
                    "%.2f".formatted(average(ofSession, Timing::waitBehindOtherSession)));
        }
    }

    private static double average(List<Timing> timings, ToLongFunction<Timing> metric) {
        return millis((long) timings.stream().mapToLong(metric).average().orElse(0));
    }

    /** Nearest-rank percentile; {@link #MAX} yields the maximum. */
    private static double percentile(List<Timing> timings, ToLongFunction<Timing> metric, double fraction) {
        long[] sorted = timings.stream().mapToLong(metric).sorted().toArray();
        int rank = (int) Math.ceil(fraction * sorted.length);
        return millis(sorted[Math.max(rank, 1) - 1]);
    }

    private static double millis(long nanos) {
        return nanos / NANOS_PER_MILLI;
    }

    private record Timing(long sessionId, long lockWait, long waitBehindOtherSession, long total) {
    }
}
