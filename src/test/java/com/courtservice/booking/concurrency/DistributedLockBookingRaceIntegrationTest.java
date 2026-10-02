package com.courtservice.booking.concurrency;

import com.courtservice.booking.config.BookingProperties;
import com.courtservice.booking.dto.BookingCreateRequest;
import com.courtservice.booking.lock.BookingLockKeys;
import com.courtservice.booking.lock.HeldBookingLock;
import com.courtservice.booking.service.BookingService;
import com.courtservice.booking.strategy.BookingConcurrencyMode;
import com.courtservice.booking.strategy.impl.DistributedLockBookingStrategy;
import com.courtservice.common.config.RedisConnectionProperties;
import com.courtservice.common.error.BusinessException;
import com.courtservice.common.error.ErrorCode;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>Purpose:</b> prove that the distributed-lock strategy (one Redisson lock per session in Redis,
 * held around the whole transaction) never oversells, also across application instances, and that
 * its lock behaves as designed: it times out, it is per session, and the debug hold keeps no
 * database connection.
 *
 * <p><b>Steps</b> (see {@link BookingRaceIntegrationTestBase}): create a court, create the
 * session(s), create the members, then let the members book at the same moment. The edge cases
 * hold a session's lock from another thread with {@link HeldBookingLock} before booking.
 *
 * <p><b>Expected:</b> races are exact. They run with a 30-second wait time so that no request times
 * out: with the default 3 seconds the number of {@code LOCK_TIMEOUT} rejections would depend on
 * the machine's speed. Lock timeouts are covered by their own case with a short wait time.
 *
 * <p>Every strategy here is built in code with the wait time and debug hold it needs, so this
 * class shares the base Spring context instead of starting its own containers.
 */
class DistributedLockBookingRaceIntegrationTest extends BookingRaceIntegrationTestBase {

    private static final Duration RACE_WAIT = Duration.ofSeconds(30);
    private static final Duration SHORT_WAIT = Duration.ofMillis(200);
    private static final Duration DEBUG_HOLD = Duration.ofSeconds(1);
    private static final Duration MID_HOLD = Duration.ofMillis(300);
    private static final Duration UNBLOCKED_LIMIT = Duration.ofSeconds(1);
    private static final int SAME_MEMBER_REQUESTS = 20;

    @Autowired
    private RedisConnectionProperties redisConnectionProperties;

    @Autowired
    private DataSource dataSource;

    /** 500 members race for 10 seats on one instance: exactly 10 win. */
    @Test
    void exactlyCapacityMembersWinTheRace() throws InterruptedException {
        // given: steps 1 to 3
        long sessionId = createSession(createCourt(), CAPACITY);
        List<Long> memberIds = createMembers(MEMBERS);
        BookingService bookingService = bookingServiceUsing(strategyWith(redissonClient, RACE_WAIT, Duration.ZERO));

        // when: step 4
        RaceResult result = race(everyMemberBooks(bookingService, sessionId, memberIds));

        // then
        logReport("DISTRIBUTED_LOCK: " + MEMBERS + " members, " + CAPACITY + " seats", result, sessionId, CAPACITY);
        assertFilledExactly(result, sessionId, MEMBERS, CAPACITY);
    }

    /**
     * 500 members race for 10 seats, alternating between two instances. Two Redisson clients have
     * different UUIDs, exactly like two application instances, so their bookings exclude each other
     * only through the lock in Redis.
     */
    @Test
    void twoInstancesSharingTheLockFillTheSessionExactly() throws InterruptedException {
        // given: steps 1 to 3, plus a second instance
        long sessionId = createSession(createCourt(), CAPACITY);
        List<Long> memberIds = createMembers(MEMBERS);
        RedissonClient secondInstanceClient = newRedissonClient();
        try {
            BookingService firstInstance = bookingServiceUsing(strategyWith(redissonClient, RACE_WAIT, Duration.ZERO));
            BookingService secondInstance =
                    bookingServiceUsing(strategyWith(secondInstanceClient, RACE_WAIT, Duration.ZERO));
            List<BookingRequest> requests = new ArrayList<>(memberIds.size());
            for (int i = 0; i < memberIds.size(); i++) {
                requests.add(new BookingRequest(i % 2 == 0 ? firstInstance : secondInstance, sessionId,
                        memberIds.get(i)));
            }

            // when: step 4, members alternate between the two instances
            RaceResult result = race(requests);

            // then
            logReport("DISTRIBUTED_LOCK, two instances", result, sessionId, CAPACITY);
            assertFilledExactly(result, sessionId, MEMBERS, CAPACITY);
        } finally {
            secondInstanceClient.shutdown();
        }
    }

    /** One member sends 20 requests at once: exactly one booking, the rest are duplicates. */
    @Test
    void sameMemberRacingWithItselfBooksOnlyOnce() throws InterruptedException {
        // given
        long sessionId = createSession(createCourt(), CAPACITY);
        List<Long> sameMemberTwentyTimes = Collections.nCopies(SAME_MEMBER_REQUESTS, createMembers(1).getFirst());
        BookingService bookingService = bookingServiceUsing(strategyWith(redissonClient, RACE_WAIT, Duration.ZERO));

        // when
        RaceResult result = race(everyMemberBooks(bookingService, sessionId, sameMemberTwentyTimes));

        // then
        assertThat(result.outcomesOf(sessionId)).containsExactlyInAnyOrderEntriesOf(Map.of(
                SUCCESS, 1L,
                ErrorCode.DUPLICATE_BOOKING.name(), (long) (SAME_MEMBER_REQUESTS - 1)));
        assertThat(bookingRows(sessionId)).isEqualTo(1);
        assertThat(bookedCount(sessionId)).isEqualTo(1);
    }

    /**
     * While another thread holds the session's lock, a booking gives up after the wait time with
     * {@code LOCK_TIMEOUT} and writes nothing. The holder must be another thread, because the lock
     * is re-entrant for its own.
     */
    @Test
    void bookingTimesOutWhileAnotherThreadHoldsTheSessionLock() throws InterruptedException {
        // given
        long sessionId = createSession(createCourt(), CAPACITY);
        long memberId = createMembers(1).getFirst();
        BookingService bookingService = bookingServiceUsing(strategyWith(redissonClient, SHORT_WAIT, Duration.ZERO));

        // when / then
        try (HeldBookingLock ignored = HeldBookingLock.hold(redissonClient, sessionId)) {
            assertThatThrownBy(() -> bookingService.create(new BookingCreateRequest(sessionId, memberId)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.LOCK_TIMEOUT);
        }
        assertThat(bookingRows(sessionId)).isZero();
        assertThat(bookedCount(sessionId)).isZero();
    }

    /**
     * The lock is per session: while session 1's lock is held, a booking of session 2 completes
     * within a second. With one shared lock it would wait the full wait time and time out.
     */
    @Test
    void lockOfOneSessionDoesNotBlockBookingsOfAnother() throws InterruptedException {
        // given
        long courtId = createCourt();
        long lockedSessionId = createSession(courtId, CAPACITY);
        long otherSessionId = createSession(courtId, CAPACITY);
        long memberId = createMembers(1).getFirst();
        BookingService bookingService = bookingServiceUsing(strategyWith(redissonClient,
                bookingProperties.distributedLock().waitTime(), Duration.ZERO));

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
     * During the debug hold the lock is held but no transaction has begun, so the connection pool
     * lends out no extra connection, and the booking takes at least the hold time.
     */
    @Test
    void debugHoldKeepsTheLockWithoutHoldingADatabaseConnection() throws Exception {
        // given
        long sessionId = createSession(createCourt(), CAPACITY);
        long memberId = createMembers(1).getFirst();
        BookingService bookingService = bookingServiceUsing(strategyWith(redissonClient,
                bookingProperties.distributedLock().waitTime(), DEBUG_HOLD));
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

    private DistributedLockBookingStrategy strategyWith(RedissonClient client, Duration waitTime, Duration debugHold) {
        BookingProperties properties = new BookingProperties(BookingConcurrencyMode.DISTRIBUTED_LOCK,
                bookingProperties.optimisticRetry(), bookingProperties.soldOutCache(),
                new BookingProperties.DistributedLock(waitTime, debugHold));
        return new DistributedLockBookingStrategy(courtSessionService, memberService, bookingRepository,
                transactionTemplate, client, properties);
    }

    /** A second Redisson client, standing in for a second application instance. */
    private RedissonClient newRedissonClient() {
        Config config = new Config();
        config.useSingleServer().setAddress(redisConnectionProperties.address());
        return Redisson.create(config);
    }
}
