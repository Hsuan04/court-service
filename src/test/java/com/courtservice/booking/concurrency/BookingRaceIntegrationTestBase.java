package com.courtservice.booking.concurrency;

import com.courtservice.booking.cache.SoldOutSessionCache;
import com.courtservice.booking.config.BookingProperties;
import com.courtservice.booking.dto.BookingCreateRequest;
import com.courtservice.booking.repository.BookingRepository;
import com.courtservice.booking.service.BookingService;
import com.courtservice.booking.strategy.BookingConcurrencyMode;
import com.courtservice.booking.strategy.BookingStrategy;
import com.courtservice.booking.strategy.BookingStrategyRegistry;
import com.courtservice.common.error.BusinessException;
import com.courtservice.common.error.ErrorCode;
import com.courtservice.court.CourtService;
import com.courtservice.court.dto.CourtCreateRequest;
import com.courtservice.member.MemberService;
import com.courtservice.member.dto.MemberCreateRequest;
import com.courtservice.session.CourtSessionService;
import com.courtservice.session.dto.CourtSessionCreateRequest;
import com.courtservice.session.dto.CourtSessionResponse;
import com.courtservice.session.dto.CourtSessionUpdateRequest;
import com.courtservice.support.IntegrationTestData;
import com.courtservice.support.RedisTestData;
import com.courtservice.support.TestcontainersConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared flow of every booking race: the same four steps, through the real services, for each
 * concurrency strategy.
 *
 * <ol>
 *   <li>{@link #createCourt()}: create a court</li>
 *   <li>{@link #createSession(long, int)}: create a session of that court with a given capacity</li>
 *   <li>{@link #createMembers(int)}: create the members who will compete</li>
 *   <li>{@link #race(List)}: every member books at the same moment, one platform thread each</li>
 * </ol>
 *
 * <p>Every subclass inherits this exact configuration, so all strategy tests share one Spring
 * context and therefore one PostgreSQL and one Redis container. Each test builds its own
 * {@link BookingService} around the strategy under test, with the sold-out flag off so every
 * request really reaches the strategy; the mode configured in {@code application-local.yaml}
 * does not matter.
 *
 * <p>Platform threads are used, not virtual threads: like a servlet container's workers, and
 * because Java 21 pins a virtual thread that blocks inside {@code synchronized}.
 */
@Slf4j
@Import(TestcontainersConfiguration.class)
@SpringBootTest
abstract class BookingRaceIntegrationTestBase {

    static final int CAPACITY = 10;
    static final int MEMBERS = 500;
    static final String SUCCESS = "SUCCESS";

    private static final String COURT_ADDRESS = "Taipei";
    private static final LocalTime SESSION_START = LocalTime.of(18, 0);
    private static final LocalTime SESSION_END = LocalTime.of(20, 0);
    private static final long NANOS_PER_MILLI = 1_000_000;

    @Autowired
    CourtService courtService;

    @Autowired
    CourtSessionService courtSessionService;

    @Autowired
    MemberService memberService;

    @Autowired
    BookingRepository bookingRepository;

    @Autowired
    BookingProperties bookingProperties;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    RedissonClient redissonClient;

    @Autowired
    List<BookingStrategy> strategies;

    @Autowired
    private SoldOutSessionCache soldOutSessionCache;

    @Autowired
    ApplicationEventPublisher eventPublisher;

    /** The application's own service, a Spring proxy, used for cancellations; see {@link #cancel(long)}. */
    @Autowired
    private BookingService applicationBookingService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private IntegrationTestData data;

    /**
     * Starts every test from an empty database, an empty Redis and no sold-out flags. Truncation
     * restarts ids, so state left by a previous test would otherwise hit this test's session 1.
     */
    @BeforeEach
    void resetState() {
        data = new IntegrationTestData(jdbcTemplate);
        data.truncateAll();
        new RedisTestData(redissonClient).flushAll();
        soldOutSessionCache.invalidateAll();
    }

    // ---- Step 1 to 3: create the data through the services ------------------------------------

    /** Step 1: creates a court. */
    long createCourt() {
        return courtService.create(new CourtCreateRequest("Race Court", COURT_ADDRESS)).id();
    }

    /** Step 2: creates a session of the court tomorrow evening, open for booking now. */
    long createSession(long courtId, int capacity) {
        OffsetDateTime now = OffsetDateTime.now();
        return courtSessionService.create(new CourtSessionCreateRequest(courtId, LocalDate.now().plusDays(1),
                SESSION_START, SESSION_END, capacity, now.minusHours(1), now.plusDays(1))).id();
    }

    /** Changes the capacity of a session through the service, keeping its schedule. */
    void changeCapacity(long sessionId, int capacity) {
        CourtSessionResponse session = courtSessionService.get(sessionId);
        courtSessionService.update(sessionId, new CourtSessionUpdateRequest(session.sessionDate(),
                session.startTime(), session.endTime(), capacity, session.openAt(), session.closeAt()));
    }

    /** Step 3: creates the members who will compete for the seats. */
    List<Long> createMembers(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> memberService.create(new MemberCreateRequest("Member " + i)).id())
                .toList();
    }

    // ---- Step 4: the race -----------------------------------------------------------------------

    /**
     * Step 4: releases every request at the same moment through a start gate, one platform thread
     * each, and records the outcome and timing of every request.
     *
     * @param requests who books which session, through which service
     * @return every attempt and when the gate opened
     */
    RaceResult race(List<BookingRequest> requests) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(requests.size());
        CountDownLatch start = new CountDownLatch(1);
        Queue<Attempt> attempts = new ConcurrentLinkedQueue<>();
        long gateOpenedAt;

        try (ExecutorService pool = Executors.newFixedThreadPool(requests.size())) {
            for (BookingRequest request : requests) {
                pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    long startedAt = System.nanoTime();
                    String result = book(request);
                    attempts.add(new Attempt(Thread.currentThread().getName(), request.sessionId(),
                            request.memberId(), result, startedAt, System.nanoTime()));
                    return null;
                });
            }
            ready.await();
            gateOpenedAt = System.nanoTime();
            start.countDown();
        }
        return new RaceResult(gateOpenedAt, List.copyOf(attempts));
    }

    /** Every member books the same session through the same service. */
    static List<BookingRequest> everyMemberBooks(BookingService bookingService, long sessionId, List<Long> memberIds) {
        return memberIds.stream().map(memberId -> new BookingRequest(bookingService, sessionId, memberId)).toList();
    }

    /** {@code SUCCESS}, the error code of a business rejection, or the class of any other failure. */
    private static String book(BookingRequest request) {
        try {
            request.bookingService().create(new BookingCreateRequest(request.sessionId(), request.memberId()));
            return SUCCESS;
        } catch (BusinessException e) {
            return e.getErrorCode().name();
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName();
        }
    }

    /**
     * Cancels a booking through the application's {@link BookingService} bean. The services built
     * by {@link #bookingServiceUsing(BookingStrategy)} are plain objects, not Spring proxies, so
     * {@code cancel}'s {@code @Transactional} would not apply to them. Cancellation does not depend
     * on the strategy, so the bean's configured strategy does not matter.
     */
    void cancel(long bookingId) {
        applicationBookingService.cancel(bookingId);
    }

    // ---- Strategy wiring ------------------------------------------------------------------------

    /** The strategy bean implementing a mode. */
    BookingStrategy strategy(BookingConcurrencyMode mode) {
        return strategies.stream().filter(candidate -> candidate.mode() == mode).findFirst().orElseThrow();
    }

    /** A {@link BookingService} that uses only the given strategy, with the sold-out flag off. */
    BookingService bookingServiceUsing(BookingStrategy strategy) {
        BookingProperties.SoldOutCache configured = bookingProperties.soldOutCache();
        BookingProperties properties = new BookingProperties(strategy.mode(), bookingProperties.optimisticRetry(),
                new BookingProperties.SoldOutCache(false, configured.ttl(), configured.maximumSize()),
                bookingProperties.distributedLock(),
                bookingProperties.jvmLock());
        BookingStrategyRegistry registry = new BookingStrategyRegistry(List.of(strategy), properties);
        return new BookingService(bookingRepository, courtSessionService, memberService, registry,
                new SoldOutSessionCache(properties, new SimpleMeterRegistry()), eventPublisher);
    }

    // ---- Checks and report ----------------------------------------------------------------------

    int bookingRows(long sessionId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM booking WHERE court_session_id = ?", Integer.class, sessionId);
    }

    int confirmedBookingRows(long sessionId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM booking WHERE court_session_id = ? AND status = 'CONFIRMED'", Integer.class,
                sessionId);
    }

    int bookedCount(long sessionId) {
        return data.bookedCount(sessionId);
    }

    /**
     * Asserts the result every safe strategy must reach: exactly {@code capacity} successes, every
     * other member rejected as full, and the booking rows and {@code booked_count} both equal to
     * the capacity (no oversell, no lost update).
     */
    void assertFilledExactly(RaceResult result, long sessionId, int members, int capacity) {
        assertThat(result.outcomesOf(sessionId)).as("outcomes of session %d", sessionId)
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        SUCCESS, (long) capacity,
                        ErrorCode.SESSION_FULL.name(), (long) (members - capacity)));
        assertThat(bookingRows(sessionId)).as("booking rows of session %d", sessionId).isEqualTo(capacity);
        assertThat(bookedCount(sessionId)).as("booked_count of session %d", sessionId).isEqualTo(capacity);
    }

    /** Logs one summary block for a session after a race. */
    void logReport(String title, RaceResult result, long sessionId, int capacity) {
        int rows = bookingRows(sessionId);
        int booked = bookedCount(sessionId);
        log.info("=========== {} ===========", title);
        log.info("Requests: {}   Capacity: {}   Elapsed: {} ms", result.attemptsOf(sessionId).size(), capacity,
                result.elapsedMillis());
        log.info("Outcomes                  : {}", new TreeMap<>(result.outcomesOf(sessionId)));
        log.info("Rows in booking table     : {}", rows);
        log.info("court_session.booked_count: {}", booked);
        log.info("Oversold                  : {}", Math.max(0, rows - capacity));
        log.info("Lost updates              : {} (rows - booked_count)", rows - booked);
    }

    // ---- Types ----------------------------------------------------------------------------------

    /** One member booking one session through one {@link BookingService}. */
    record BookingRequest(BookingService bookingService, long sessionId, long memberId) {
    }

    /** What one request did and when: nanosecond timestamps from {@link System#nanoTime()}. */
    record Attempt(String thread, long sessionId, long memberId, String result, long startNanos, long endNanos) {
    }

    /**
     * Every attempt of a race.
     *
     * @param gateOpenedAt when the start gate opened, from {@link System#nanoTime()}
     * @param attempts     every request, in completion order of their threads
     */
    record RaceResult(long gateOpenedAt, List<Attempt> attempts) {

        List<Attempt> attemptsOf(long sessionId) {
            return attempts.stream().filter(attempt -> attempt.sessionId() == sessionId).toList();
        }

        Map<String, Long> outcomesOf(long sessionId) {
            return attemptsOf(sessionId).stream()
                    .collect(Collectors.groupingBy(Attempt::result, Collectors.counting()));
        }

        long count(long sessionId, String outcome) {
            return outcomesOf(sessionId).getOrDefault(outcome, 0L);
        }

        long elapsedMillis() {
            long lastEnd = attempts.stream().mapToLong(Attempt::endNanos).max().orElse(gateOpenedAt);
            return (lastEnd - gateOpenedAt) / NANOS_PER_MILLI;
        }
    }
}
