package com.courtservice.booking.strategy;

import com.courtservice.IntegrationTestData;
import com.courtservice.RedisTestData;
import com.courtservice.TestcontainersConfiguration;
import com.courtservice.booking.cache.SoldOutSessionCache;
import com.courtservice.booking.config.BookingProperties;
import com.courtservice.booking.dto.BookingCreateRequest;
import com.courtservice.booking.lock.BookingLockKeys;
import com.courtservice.booking.lock.HeldBookingLock;
import com.courtservice.booking.repository.BookingRepository;
import com.courtservice.booking.service.BookingService;
import com.courtservice.booking.strategy.impl.DistributedLockBookingStrategy;
import com.courtservice.common.config.RedisConnectionProperties;
import com.courtservice.common.error.BusinessException;
import com.courtservice.common.error.ErrorCode;
import com.courtservice.member.MemberService;
import com.courtservice.session.CourtSessionService;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the distributed-lock strategy against a real Redis.
 *
 * <p>The context's wait time is 30 seconds, long enough that no request of the two-instance race
 * times out: that race asserts exact outcomes, and with the default 3 seconds the number of
 * {@code LOCK_TIMEOUT} rejections would depend on the machine's speed. Lock timeouts are covered
 * by their own case with a short wait time.
 *
 * <p>Every {@link BookingService} here is built with the sold-out flag off, so every request
 * really reaches the lock.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.booking.distributed-lock.wait-time=30s")
class DistributedLockBookingStrategyIntegrationTest {

    private static final int CAPACITY = 10;
    private static final int MEMBERS = 500;
    private static final String SUCCESS = "SUCCESS";
    private static final Duration SHORT_WAIT = Duration.ofMillis(200);
    private static final Duration DEBUG_HOLD = Duration.ofSeconds(1);
    private static final Duration MID_HOLD = Duration.ofMillis(300);
    private static final Duration UNBLOCKED_LIMIT = Duration.ofSeconds(1);

    @Autowired
    private DistributedLockBookingStrategy distributedLockBookingStrategy;

    @Autowired
    private BookingProperties bookingProperties;

    @Autowired
    private RedisConnectionProperties redisConnectionProperties;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private CourtSessionService courtSessionService;

    @Autowired
    private MemberService memberService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    private IntegrationTestData data;
    private long courtId;

    @BeforeEach
    void setUp() {
        data = new IntegrationTestData(jdbcTemplate);
        data.truncateAll();
        new RedisTestData(redissonClient).flushAll();
        courtId = data.insertCourt("Court A");
    }

    /**
     * Two Redisson clients have different UUIDs, exactly like two application instances, so their
     * locks only exclude each other through Redis.
     */
    @Test
    void twoInstancesSharingTheLockFillTheSessionExactly() throws InterruptedException {
        // given
        long sessionId = data.insertSession(courtId, CAPACITY);
        List<Long> memberIds = IntStream.range(0, MEMBERS)
                .mapToObj(i -> data.insertMember("Member " + i))
                .toList();
        RedissonClient secondInstanceClient = newRedissonClient();
        try {
            BookingService firstInstance = bookingServiceUsing(distributedLockBookingStrategy);
            BookingService secondInstance = bookingServiceUsing(new DistributedLockBookingStrategy(
                    courtSessionService, memberService, bookingRepository, transactionTemplate,
                    secondInstanceClient, bookingProperties));

            // when: members alternate between the two instances
            Map<String, Integer> outcomes = race(sessionId, memberIds, firstInstance, secondInstance);

            // then
            assertThat(outcomes).containsExactlyInAnyOrderEntriesOf(Map.of(
                    SUCCESS, CAPACITY,
                    ErrorCode.SESSION_FULL.name(), MEMBERS - CAPACITY));
            assertThat(bookingRows(sessionId)).isEqualTo(CAPACITY);
            assertThat(data.bookedCount(sessionId)).isEqualTo(CAPACITY);
        } finally {
            secondInstanceClient.shutdown();
        }
    }

    @Test
    void bookingTimesOutWhileAnotherThreadHoldsTheSessionLock() throws InterruptedException {
        // given
        long sessionId = data.insertSession(courtId, CAPACITY);
        long memberId = data.insertMember("Alice");
        BookingService bookingService = bookingServiceUsing(strategyWith(SHORT_WAIT, Duration.ZERO));

        // when / then: the holder is another thread, because the lock is re-entrant for its own
        try (HeldBookingLock ignored = HeldBookingLock.hold(redissonClient, sessionId)) {
            assertThatThrownBy(() -> bookingService.create(new BookingCreateRequest(sessionId, memberId)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.LOCK_TIMEOUT);
        }
        assertThat(bookingRows(sessionId)).isZero();
        assertThat(data.bookedCount(sessionId)).isZero();
    }

    @Test
    void lockOfOneSessionDoesNotBlockBookingsOfAnother() throws InterruptedException {
        // given
        long lockedSessionId = data.insertSession(courtId, CAPACITY);
        long otherSessionId = data.insertSession(courtId, CAPACITY);
        long memberId = data.insertMember("Alice");
        BookingService bookingService = bookingServiceUsing(distributedLockBookingStrategy);

        try (HeldBookingLock ignored = HeldBookingLock.hold(redissonClient, lockedSessionId)) {
            // when
            long startedAt = System.nanoTime();
            bookingService.create(new BookingCreateRequest(otherSessionId, memberId));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

            // then
            assertThat(elapsed).isLessThan(UNBLOCKED_LIMIT);
        }
        assertThat(bookingRows(otherSessionId)).isEqualTo(1);
    }

    /**
     * While the debug hold runs the lock is held, but no transaction has begun, so the pool lends
     * out no extra connection.
     */
    @Test
    void debugHoldKeepsTheLockWithoutHoldingADatabaseConnection() throws Exception {
        // given
        long sessionId = data.insertSession(courtId, CAPACITY);
        long memberId = data.insertMember("Alice");
        BookingService bookingService = bookingServiceUsing(strategyWith(bookingProperties.distributedLock().waitTime(),
                DEBUG_HOLD));
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        int activeBefore = hikari.getHikariPoolMXBean().getActiveConnections();

        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            // when
            long startedAt = System.nanoTime();
            Future<?> booking = pool.submit(() -> bookingService.create(new BookingCreateRequest(sessionId, memberId)));
            Thread.sleep(MID_HOLD);
            boolean lockedMidHold = redissonClient.getLock(BookingLockKeys.forSession(sessionId)).isLocked();
            int activeMidHold = hikari.getHikariPoolMXBean().getActiveConnections();
            booking.get();
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

            // then
            assertThat(lockedMidHold).as("lock held during the debug hold").isTrue();
            assertThat(activeMidHold).as("connections in use during the debug hold").isEqualTo(activeBefore);
            assertThat(elapsed).isGreaterThanOrEqualTo(DEBUG_HOLD);
        }
        assertThat(bookingRows(sessionId)).isEqualTo(1);
    }

    private DistributedLockBookingStrategy strategyWith(Duration waitTime, Duration debugHold) {
        BookingProperties properties = new BookingProperties(BookingConcurrencyMode.DISTRIBUTED_LOCK,
                bookingProperties.optimisticRetry(), bookingProperties.soldOutCache(),
                new BookingProperties.DistributedLock(waitTime, debugHold));
        return new DistributedLockBookingStrategy(courtSessionService, memberService, bookingRepository,
                transactionTemplate, redissonClient, properties);
    }

    private BookingService bookingServiceUsing(DistributedLockBookingStrategy strategy) {
        BookingProperties.SoldOutCache configured = bookingProperties.soldOutCache();
        BookingProperties properties = new BookingProperties(BookingConcurrencyMode.DISTRIBUTED_LOCK,
                bookingProperties.optimisticRetry(),
                new BookingProperties.SoldOutCache(false, configured.ttl(), configured.maximumSize()),
                bookingProperties.distributedLock());
        BookingStrategyRegistry registry = new BookingStrategyRegistry(List.of(strategy), properties);
        return new BookingService(bookingRepository, courtSessionService, memberService, registry,
                new SoldOutSessionCache(properties, new SimpleMeterRegistry()));
    }

    private RedissonClient newRedissonClient() {
        Config config = new Config();
        config.useSingleServer().setAddress(redisConnectionProperties.address());
        return Redisson.create(config);
    }

    /**
     * Starts one booking per member at the same moment on platform threads, sending even-indexed
     * members to the first instance and odd-indexed ones to the second.
     */
    private Map<String, Integer> race(long sessionId, List<Long> memberIds, BookingService first,
                                      BookingService second) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(memberIds.size());
        CountDownLatch start = new CountDownLatch(1);
        Map<String, Integer> outcomes = new ConcurrentHashMap<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(memberIds.size())) {
            for (int i = 0; i < memberIds.size(); i++) {
                BookingService instance = i % 2 == 0 ? first : second;
                Long memberId = memberIds.get(i);
                pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    outcomes.merge(book(instance, sessionId, memberId), 1, Integer::sum);
                    return null;
                });
            }
            ready.await();
            start.countDown();
        }
        return outcomes;
    }

    private static String book(BookingService bookingService, long sessionId, Long memberId) {
        try {
            bookingService.create(new BookingCreateRequest(sessionId, memberId));
            return SUCCESS;
        } catch (BusinessException e) {
            return e.getErrorCode().name();
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName();
        }
    }

    private int bookingRows(long sessionId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM booking WHERE court_session_id = ?", Integer.class, sessionId);
    }
}
