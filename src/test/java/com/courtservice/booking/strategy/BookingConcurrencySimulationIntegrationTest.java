package com.courtservice.booking.strategy;

import com.courtservice.booking.BookingService;
import com.courtservice.booking.dto.BookingCreateRequest;
import com.courtservice.common.error.BusinessException;
import com.courtservice.common.error.ErrorCode;
import com.courtservice.court.CourtService;
import com.courtservice.court.dto.CourtCreateRequest;
import com.courtservice.member.MemberService;
import com.courtservice.member.dto.MemberCreateRequest;
import com.courtservice.session.CourtSessionService;
import com.courtservice.session.dto.CourtSessionCreateRequest;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@Tag(BookingConcurrencySimulationIntegrationTest.SIMULATION_TAG)
@ActiveProfiles("local")
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=50",
        "spring.docker.compose.skip.in-tests=false",
        "spring.docker.compose.lifecycle-management=start-only"
})
class BookingConcurrencySimulationIntegrationTest {

    static final String SIMULATION_TAG = "simulation";

    private static final int CAPACITY = 10;
    private static final int MEMBERS = 500;
    private static final long POOL_SAMPLE_INTERVAL_MS = 5;
    private static final String SUCCESS = "SUCCESS";
    private static final int MAX_REPORTED_EXCEPTIONS = 5;
    private static final Set<ErrorCode> EXPECTED_REJECTIONS = EnumSet.of(
            ErrorCode.SESSION_FULL, ErrorCode.DUPLICATE_BOOKING,
            ErrorCode.CONCURRENT_MODIFICATION);

    @Autowired
    private BookingService bookingService;

    @Autowired
    private BookingStrategyRegistry bookingStrategyRegistry;

    @Autowired
    private CourtService courtService;

    @Autowired
    private CourtSessionService courtSessionService;

    @Autowired
    private MemberService memberService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    private final String runId = UUID.randomUUID().toString().substring(0, 8);

    @Test
    void membersRacingForOneSessionNeverExceedCapacity() throws InterruptedException, SQLException {
        // given
        BookingConcurrencyMode mode = bookingStrategyRegistry.active().mode();
        Long sessionId = createCourtAndSession(mode);
        List<Long> memberIds = createMembers(mode);

        CountDownLatch ready = new CountDownLatch(MEMBERS);
        CountDownLatch start = new CountDownLatch(1);
        Map<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();

        // Samples how many threads wait for a pooled connection, to show how much of the elapsed
        // time is spent queueing for the pool rather than in the database.
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        AtomicInteger peakAwaitingConnection = new AtomicInteger();

        // when: every thread waits at the start gate so that all bookings hit the session together
        long startedAt;
        try (ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
             ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            sampler.scheduleAtFixedRate(() -> peakAwaitingConnection.accumulateAndGet(
                            hikari.getHikariPoolMXBean().getThreadsAwaitingConnection(), Math::max),
                    0, POOL_SAMPLE_INTERVAL_MS, TimeUnit.MILLISECONDS);
            for (Long memberId : memberIds) {
                pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    book(sessionId, memberId, outcomes, unexpected);
                    return null;
                });
            }
            ready.await();
            startedAt = System.nanoTime();
            start.countDown();
        }
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        log.info("Connection pool size: {}   Peak threads awaiting a connection: {}",
                hikari.getMaximumPoolSize(), peakAwaitingConnection.get());

        // then
        int actualBookings = jdbc.queryForObject(
                "select count(*) from booking where court_session_id = ?", Integer.class, sessionId);
        int bookedCount = jdbc.queryForObject(
                "select booked_count from court_session where id = ?", Integer.class, sessionId);
        int success = outcomes.getOrDefault(SUCCESS, new AtomicInteger()).get();

        report(mode, sessionId, elapsedMs, outcomes, actualBookings, bookedCount, success, unexpected);

        assertThat(unexpected).as("unexpected exceptions").isEmpty();
        if (mode == BookingConcurrencyMode.NAIVE) {
            return;
        }
        assertThat(actualBookings).as("no oversell").isEqualTo(CAPACITY);
        assertThat(bookedCount).as("booked_count matches rows (no lost update)").isEqualTo(actualBookings);
        assertThat(success).as("success responses match rows").isEqualTo(actualBookings);
    }

    private void book(Long sessionId, Long memberId, Map<String, AtomicInteger> outcomes,
                      List<Throwable> unexpected) {
        try {
            bookingService.create(new BookingCreateRequest(sessionId, memberId));
            count(outcomes, SUCCESS);
        } catch (BusinessException e) {
            count(outcomes, e.getErrorCode().name());
            if (!EXPECTED_REJECTIONS.contains(e.getErrorCode())) {
                unexpected.add(e);
            }
        } catch (RuntimeException e) {
            count(outcomes, e.getClass().getSimpleName());
            unexpected.add(e);
        }
    }

    private void report(BookingConcurrencyMode mode, Long sessionId, long elapsedMs,
                        Map<String, AtomicInteger> outcomes, int actualBookings, int bookedCount, int success,
                        List<Throwable> unexpected) {
        log.info("=========== {}: run {}, session {} ===========", mode, runId, sessionId);
        log.info("Members: {}   Capacity: {}   Elapsed: {} ms", MEMBERS, CAPACITY, elapsedMs);
        log.info("Outcomes                  : {}", new TreeMap<>(outcomes));
        log.info("Rows in booking table     : {}", actualBookings);
        log.info("court_session.booked_count: {}", bookedCount);
        log.info("Oversold                  : {}", Math.max(0, actualBookings - CAPACITY));
        log.info("Lost updates              : {} (rows - booked_count)", actualBookings - bookedCount);
        log.info("Success responses vs rows : {} vs {}", success, actualBookings);
        if (actualBookings > CAPACITY) {
            log.info("--- Oversold rows (beyond capacity, in insert order) ---");
            jdbc.queryForList("select id, member_id, created_at from booking where court_session_id = ? "
                            + "order by id offset ?", sessionId, CAPACITY)
                    .forEach(row -> log.info("  OVERSOLD {}", row));
        }
        if (!unexpected.isEmpty()) {
            log.info("--- Unexpected exceptions (first {}) ---", MAX_REPORTED_EXCEPTIONS);
            unexpected.stream().limit(MAX_REPORTED_EXCEPTIONS).forEach(e -> log.info("  {}", e.toString()));
        }
        log.info("======================================================");
    }

    private Long createCourtAndSession(BookingConcurrencyMode mode) {
        Long courtId = courtService.create(
                new CourtCreateRequest("Court " + runId + " " + mode, "123 Main St")).id();
        OffsetDateTime now = OffsetDateTime.now();
        return courtSessionService.create(new CourtSessionCreateRequest(
                courtId,
                LocalDate.now().plusDays(1),
                LocalTime.of(18, 0),
                LocalTime.of(20, 0),
                CAPACITY,
                now.minusHours(1),
                now.plusDays(1)
        )).id();
    }

    private List<Long> createMembers(BookingConcurrencyMode mode) {
        return IntStream.range(0, MEMBERS)
                .mapToObj(i -> memberService.create(
                        new MemberCreateRequest("Member-" + runId + "-" + mode + "-" + i)).id())
                .toList();
    }

    private static void count(Map<String, AtomicInteger> outcomes, String key) {
        outcomes.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    }
}
