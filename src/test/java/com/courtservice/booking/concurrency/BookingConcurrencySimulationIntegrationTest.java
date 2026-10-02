package com.courtservice.booking.concurrency;

import com.courtservice.booking.cache.SoldOutSessionCache;
import com.courtservice.booking.config.BookingProperties;
import com.courtservice.booking.dto.BookingCreateRequest;
import com.courtservice.booking.service.BookingService;
import com.courtservice.booking.strategy.BookingConcurrencyMode;
import com.courtservice.booking.strategy.BookingStrategyRegistry;
import com.courtservice.common.error.BusinessException;
import com.courtservice.common.error.ErrorCode;
import com.courtservice.common.logging.SqlExecutionLogger;
import com.courtservice.court.CourtService;
import com.courtservice.court.dto.CourtCreateRequest;
import com.courtservice.member.MemberService;
import com.courtservice.member.dto.MemberCreateRequest;
import com.courtservice.session.CourtSessionService;
import com.courtservice.session.dto.CourtSessionCreateRequest;
import com.courtservice.support.CapturedLogEvents;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
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

/**
 * <b>Purpose:</b> load simulation of the strategy configured in {@code application-local.yaml},
 * against the Docker Compose PostgreSQL and Redis rather than Testcontainers, comparing runs with
 * the sold-out flag on and off.
 *
 * <p><b>Steps:</b> create a court and a session, create two waves of members, race the first wave
 * for the seats, then send the second wave at the already full session; report outcomes, SQL
 * statements and fast rejects per wave.
 *
 * <p><b>Expected:</b> no oversell in the first wave; in the second wave every request is rejected
 * as full, and with the flag enabled without a single SQL statement.
 *
 * <p>Excluded from the default build. Run it with
 * {@code ./mvnw test -Dsurefire.excludedGroups= -Dtest=BookingConcurrencySimulationIntegrationTest},
 * adding {@code -Dapp.booking.sold-out-cache.enabled=false} to compare against a run without the
 * sold-out flag.
 *
 * <p>Two waves hit the same session. The first races {@code MEMBERS} members for its seats. The
 * second sends another {@code MEMBERS} members once the session is already full: with the flag
 * enabled every one of them must be rejected by the flag without a single SQL statement.
 *
 * <p>Both waves' members are created before the first wave starts, so the time spent creating them
 * cannot let the flag's TTL run out between the waves.
 */
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
    private static final String FIRST_WAVE = "first wave";
    private static final String SECOND_WAVE = "second wave";
    private static final int MAX_REPORTED_EXCEPTIONS = 5;
    private static final Set<ErrorCode> EXPECTED_REJECTIONS = EnumSet.of(
            ErrorCode.SESSION_FULL, ErrorCode.DUPLICATE_BOOKING,
            ErrorCode.CONCURRENT_MODIFICATION, ErrorCode.LOCK_TIMEOUT);

    @Autowired
    private BookingService bookingService;

    @Autowired
    private BookingStrategyRegistry bookingStrategyRegistry;

    @Autowired
    private BookingProperties bookingProperties;

    @Autowired
    private CourtService courtService;

    @Autowired
    private CourtSessionService courtSessionService;

    @Autowired
    private MemberService memberService;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    private final String runId = UUID.randomUUID().toString().substring(0, 8);

    @Test
    void membersRacingForOneSessionNeverExceedCapacity() throws InterruptedException, SQLException {
        // given
        BookingConcurrencyMode mode = bookingStrategyRegistry.active().mode();
        boolean soldOutFlagEnabled = bookingProperties.soldOutCache().enabled();
        Long sessionId = createCourtAndSession(mode);
        List<Long> firstWaveMembers = createMembers(mode, FIRST_WAVE);
        List<Long> secondWaveMembers = createMembers(mode, SECOND_WAVE);

        // when
        Wave first = runWave(FIRST_WAVE, sessionId, firstWaveMembers);
        int rowsAfterFirstWave = bookingRows(sessionId);
        int bookedCountAfterFirstWave = bookedCount(sessionId);
        Wave second = runWave(SECOND_WAVE, sessionId, secondWaveMembers);

        // then
        int rowsAfterSecondWave = bookingRows(sessionId);
        int firstWaveSuccess = first.count(SUCCESS);
        report(mode, soldOutFlagEnabled, sessionId, first, second, rowsAfterFirstWave, bookedCountAfterFirstWave,
                rowsAfterSecondWave);

        assertThat(first.unexpected()).as("unexpected exceptions in the first wave").isEmpty();
        assertThat(second.unexpected()).as("unexpected exceptions in the second wave").isEmpty();
        if (mode == BookingConcurrencyMode.NAIVE) {
            return;
        }
        assertThat(rowsAfterFirstWave).as("no oversell").isEqualTo(CAPACITY);
        assertThat(bookedCountAfterFirstWave).as("booked_count matches rows (no lost update)")
                .isEqualTo(rowsAfterFirstWave);
        assertThat(firstWaveSuccess).as("success responses match rows").isEqualTo(rowsAfterFirstWave);

        assertThat(second.count(ErrorCode.SESSION_FULL.name())).as("second wave all rejected as full")
                .isEqualTo(MEMBERS);
        assertThat(rowsAfterSecondWave).as("second wave booked nothing").isEqualTo(CAPACITY);
        if (soldOutFlagEnabled) {
            assertThat(second.fastRejected()).as("second wave rejected by the sold-out flag").isEqualTo(MEMBERS);
            assertThat(second.sqlStatements()).as("second wave issued no SQL").isZero();
        }
    }

    /**
     * Releases every member of one wave at the same moment and records the outcomes, the elapsed
     * time, how many requests the sold-out flag rejected, and the SQL statements issued meanwhile.
     */
    private Wave runWave(String label, Long sessionId, List<Long> memberIds)
            throws InterruptedException, SQLException {
        CountDownLatch ready = new CountDownLatch(memberIds.size());
        CountDownLatch start = new CountDownLatch(1);
        Map<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        double fastRejectsBefore = fastRejects();

        // Samples how many threads wait for a pooled connection, to show how much of the elapsed
        // time is spent queueing for the pool rather than in the database.
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        AtomicInteger peakAwaitingConnection = new AtomicInteger();

        long startedAt;
        // Every SQL statement is logged at DEBUG under the local profile (log-all-queries), so the
        // captured events count the statements this wave issued.
        try (CapturedLogEvents sql = CapturedLogEvents.attach(SqlExecutionLogger.class.getName())) {
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
            return new Wave(label, elapsedMs, outcomes, unexpected, (long) (fastRejects() - fastRejectsBefore),
                    sql.all().size(), hikari.getMaximumPoolSize(), peakAwaitingConnection.get());
        }
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

    private void report(BookingConcurrencyMode mode, boolean soldOutFlagEnabled, Long sessionId, Wave first,
                        Wave second, int rowsAfterFirstWave, int bookedCountAfterFirstWave,
                        int rowsAfterSecondWave) {
        log.info("=========== {}: run {}, session {} ===========", mode, runId, sessionId);
        log.info("Members per wave: {}   Capacity: {}   Sold-out flag: {}", MEMBERS, CAPACITY,
                soldOutFlagEnabled ? "enabled" : "disabled");
        reportWave(first);
        log.info("Rows in booking table     : {}", rowsAfterFirstWave);
        log.info("court_session.booked_count: {}", bookedCountAfterFirstWave);
        log.info("Oversold                  : {}", Math.max(0, rowsAfterFirstWave - CAPACITY));
        log.info("Lost updates              : {} (rows - booked_count)", rowsAfterFirstWave - bookedCountAfterFirstWave);
        log.info("Success responses vs rows : {} vs {}", first.count(SUCCESS), rowsAfterFirstWave);
        if (rowsAfterFirstWave > CAPACITY) {
            log.info("--- Oversold rows (beyond capacity, in insert order) ---");
            jdbc.queryForList("select id, member_id, created_at from booking where court_session_id = ? "
                            + "order by id offset ?", sessionId, CAPACITY)
                    .forEach(row -> log.info("  OVERSOLD {}", row));
        }
        reportWave(second);
        log.info("Rows in booking table     : {}", rowsAfterSecondWave);
        log.info("======================================================");
    }

    private static void reportWave(Wave wave) {
        log.info("--- {} ---", wave.label());
        log.info("Elapsed                   : {} ms", wave.elapsedMs());
        log.info("Outcomes                  : {}", new TreeMap<>(wave.outcomes()));
        log.info("Fast rejected by sold-out flag: {}", wave.fastRejected());
        log.info("Entered the strategy      : {}", MEMBERS - wave.fastRejected());
        log.info("SQL statements            : {}", wave.sqlStatements());
        log.info("Connection pool size: {}   Peak threads awaiting a connection: {}",
                wave.poolSize(), wave.peakAwaitingConnection());
        if (!wave.unexpected().isEmpty()) {
            log.info("Unexpected exceptions (first {}):", MAX_REPORTED_EXCEPTIONS);
            wave.unexpected().stream().limit(MAX_REPORTED_EXCEPTIONS).forEach(e -> log.info("  {}", e.toString()));
        }
    }

    private double fastRejects() {
        return meterRegistry.get(SoldOutSessionCache.FAST_REJECT_METRIC).counter().count();
    }

    private int bookingRows(Long sessionId) {
        return jdbc.queryForObject("select count(*) from booking where court_session_id = ?", Integer.class,
                sessionId);
    }

    private int bookedCount(Long sessionId) {
        return jdbc.queryForObject("select booked_count from court_session where id = ?", Integer.class,
                sessionId);
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

    private List<Long> createMembers(BookingConcurrencyMode mode, String wave) {
        return IntStream.range(0, MEMBERS)
                .mapToObj(i -> memberService.create(
                        new MemberCreateRequest("Member-" + runId + "-" + mode + "-" + wave + "-" + i)).id())
                .toList();
    }

    private static void count(Map<String, AtomicInteger> outcomes, String key) {
        outcomes.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    }

    private record Wave(String label, long elapsedMs, Map<String, AtomicInteger> outcomes, List<Throwable> unexpected,
                        long fastRejected, int sqlStatements, int poolSize, int peakAwaitingConnection) {

        int count(String outcome) {
            return outcomes.getOrDefault(outcome, new AtomicInteger()).get();
        }
    }
}
