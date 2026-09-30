package com.courtservice.booking.strategy;

import com.courtservice.IntegrationTestData;
import com.courtservice.TestcontainersConfiguration;
import com.courtservice.booking.BookingRepository;
import com.courtservice.booking.BookingService;
import com.courtservice.booking.SoldOutSessionCache;
import com.courtservice.booking.dto.BookingCreateRequest;
import com.courtservice.booking.strategy.impl.SynchronizedBookingStrategy;
import com.courtservice.common.error.BusinessException;
import com.courtservice.common.error.ErrorCode;
import com.courtservice.member.MemberService;
import com.courtservice.session.CourtSessionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shows the cost of one JVM-wide lock: 500 members race for the 10 seats of session 1 while another
 * 500 race for the 10 seats of an unrelated session 2, and all 1000 bookings still queue on the same
 * {@code synchronized} lock.
 *
 * <p>The outcome is exact whatever the thread timing, so it is asserted exactly and the test runs in
 * the default build. Timings are only logged, never asserted, because they depend on the machine.
 *
 * <p>Requests run on platform threads, like a servlet container's worker pool. On Java 21 a virtual
 * thread blocked on or doing I/O inside {@code synchronized} pins its carrier thread, which would
 * distort the measurement.
 *
 * <p>Lock wait is derived from completion times instead of being measured inside the strategy, so
 * production code carries no instrumentation: the lock serializes every booking, so a request
 * acquires it approximately when the request completing just before it finishes.
 */
@Slf4j
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SynchronizedBookingStrategyIntegrationTest {

    private static final int CAPACITY = 10;
    private static final int MEMBERS_PER_SESSION = 500;
    private static final int TOTAL_REQUESTS = 2 * MEMBERS_PER_SESSION;
    private static final String SUCCESS = "SUCCESS";
    private static final double NANOS_PER_MILLI = 1_000_000.0;
    private static final double P50 = 0.50;
    private static final double P95 = 0.95;
    private static final double MAX = 1.0;
    private static final String ROW_HEADER_FORMAT = "%5s | %-18s | %7s | %6s | %-12s | %10s | %9s | %10s";
    private static final String ROW_FORMAT = "%5d | %-18s | %7s | %6d | %-12s | %10.2f | %9.2f | %10.2f";

    @Autowired
    private SynchronizedBookingStrategy synchronizedBookingStrategy;

    @Autowired
    private BookingProperties bookingProperties;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private CourtSessionService courtSessionService;

    @Autowired
    private MemberService memberService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private IntegrationTestData data;

    @BeforeEach
    void setUp() {
        data = new IntegrationTestData(jdbcTemplate);
        data.truncateAll();
    }

    @Test
    void twoUnrelatedSessionsFillExactlyWhileEveryBookingQueuesOnOneLock() throws InterruptedException {
        // given
        long sessionOne = data.insertSession(data.insertCourt("Court 1"), CAPACITY);
        long sessionTwo = data.insertSession(data.insertCourt("Court 2"), CAPACITY);
        List<Request> requests = new ArrayList<>(TOTAL_REQUESTS);
        IntStream.range(0, MEMBERS_PER_SESSION).forEach(i ->
                requests.add(new Request(sessionOne, data.insertMember("Court 1 member " + i))));
        IntStream.range(0, MEMBERS_PER_SESSION).forEach(i ->
                requests.add(new Request(sessionTwo, data.insertMember("Court 2 member " + i))));
        BookingService bookingService = bookingServiceUsingSynchronizedStrategy();

        // when
        Race race = race(bookingService, requests);

        // then
        report(race, sessionOne, sessionTwo);
        for (long sessionId : List.of(sessionOne, sessionTwo)) {
            assertThat(outcomesOf(race.attempts(), sessionId))
                    .as("outcomes of session %d", sessionId)
                    .containsExactlyInAnyOrderEntriesOf(Map.of(
                            SUCCESS, (long) CAPACITY,
                            ErrorCode.SESSION_FULL.name(), (long) (MEMBERS_PER_SESSION - CAPACITY)));
            assertThat(bookingRows(sessionId)).as("booking rows of session %d", sessionId).isEqualTo(CAPACITY);
            assertThat(data.bookedCount(sessionId)).as("booked_count of session %d", sessionId).isEqualTo(CAPACITY);
        }
    }

    /**
     * Builds a {@link BookingService} around the synchronized strategy directly, so the test does
     * not depend on the mode configured in {@code app.booking.concurrency-mode}. The sold-out flag
     * is off, so every request queues on the lock as the table is meant to show.
     */
    private BookingService bookingServiceUsingSynchronizedStrategy() {
        BookingProperties properties = new BookingProperties(BookingConcurrencyMode.SYNCHRONIZED,
                bookingProperties.optimisticRetry(), withoutSoldOutFlag());
        BookingStrategyRegistry registry = new BookingStrategyRegistry(List.of(synchronizedBookingStrategy),
                properties);
        return new BookingService(bookingRepository, courtSessionService, memberService, registry,
                new SoldOutSessionCache(properties, new SimpleMeterRegistry()));
    }

    /** Keeps the sold-out flag off, so the strategy under test sees every request. */
    private BookingProperties.SoldOutCache withoutSoldOutFlag() {
        BookingProperties.SoldOutCache configured = bookingProperties.soldOutCache();
        return new BookingProperties.SoldOutCache(false, configured.ttl(), configured.maximumSize());
    }

    /** Releases every request at the same moment, one platform thread each, and records its timing. */
    private Race race(BookingService bookingService, List<Request> requests) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(requests.size());
        CountDownLatch start = new CountDownLatch(1);
        Queue<Attempt> attempts = new ConcurrentLinkedQueue<>();
        long gateOpenedAt;

        try (ExecutorService pool = Executors.newFixedThreadPool(requests.size())) {
            for (Request request : requests) {
                pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    long startedAt = System.nanoTime();
                    String result = book(bookingService, request);
                    attempts.add(new Attempt(Thread.currentThread().getName(), request.sessionId(),
                            request.memberId(), result, startedAt, System.nanoTime()));
                    return null;
                });
            }
            ready.await();
            gateOpenedAt = System.nanoTime();
            start.countDown();
        }
        return new Race(gateOpenedAt, List.copyOf(attempts));
    }

    private static String book(BookingService bookingService, Request request) {
        try {
            bookingService.create(new BookingCreateRequest(request.sessionId(), request.memberId()));
            return SUCCESS;
        } catch (BusinessException e) {
            return e.getErrorCode().name();
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName();
        }
    }

    private static Map<String, Long> outcomesOf(List<Attempt> attempts, long sessionId) {
        return attempts.stream()
                .filter(attempt -> attempt.sessionId() == sessionId)
                .collect(Collectors.groupingBy(Attempt::result, Collectors.counting()));
    }

    private int bookingRows(long sessionId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM booking WHERE court_session_id = ?", Integer.class, sessionId);
    }

    /**
     * Logs one row per request in lock order, then a summary per session. Walking the requests in
     * completion order, each one is taken to acquire the lock when the previous one completed (or
     * when it started, if later); the lock time held so far by the other session's requests is the
     * part of its wait caused purely by sharing one lock with an unrelated session.
     */
    private static void report(Race race, long sessionOne, long sessionTwo) {
        List<Attempt> inLockOrder = race.attempts().stream()
                .sorted(Comparator.comparingLong(Attempt::endNanos))
                .toList();
        Map<Long, String> sessionLabels = Map.of(sessionOne, "1", sessionTwo, "2");
        Map<Long, Long> heldBySession = new HashMap<>(Map.of(sessionOne, 0L, sessionTwo, 0L));
        List<Timing> timings = new ArrayList<>(inLockOrder.size());

        log.info("=========== SYNCHRONIZED: per-thread lock wait (lock order) ===========");
        log.info(ROW_HEADER_FORMAT.formatted("#", "thread", "session", "member", "result",
                "wait ms", "hold ms", "total ms"));
        long previousEnd = race.gateOpenedAt();
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
                "%.2f".formatted(millis(previousEnd - race.gateOpenedAt())));
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
        log.info("=====================================================================");
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

    private record Request(long sessionId, long memberId) {
    }

    private record Attempt(String thread, long sessionId, long memberId, String result,
                           long startNanos, long endNanos) {
    }

    private record Race(long gateOpenedAt, List<Attempt> attempts) {
    }

    private record Timing(long sessionId, long lockWait, long waitBehindOtherSession, long total) {
    }
}
