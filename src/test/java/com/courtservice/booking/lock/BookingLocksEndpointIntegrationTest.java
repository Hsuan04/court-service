package com.courtservice.booking.lock;

import com.courtservice.RedisTestData;
import com.courtservice.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lists locks held through real Redisson locks in Redis. Each lock is held by a thread of its own
 * through {@link HeldBookingLock}, as a booking in progress would hold it.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.booking.concurrency-mode=distributed-lock")
class BookingLocksEndpointIntegrationTest {

    private static final long SESSION_A = 1L;
    private static final long SESSION_B = 2L;
    /** Redisson records a holder as {@code <client UUID>:<thread id>}. */
    private static final String HOLDER_FORMAT = "[0-9a-f-]{36}:\\d+";

    @Autowired
    private BookingLocksEndpoint endpoint;

    @Autowired
    private RedissonClient redissonClient;

    @BeforeEach
    void setUp() {
        new RedisTestData(redissonClient).flushAll();
    }

    @Test
    void listsNothingWhileNoLockIsHeld() {
        // when
        List<BookingLockView> locks = endpoint.locks();

        // then
        assertThat(locks).isEmpty();
    }

    @Test
    void listsTheHeldLockWithItsHolderAndTtl() throws InterruptedException {
        try (HeldBookingLock ignored = HeldBookingLock.hold(redissonClient, SESSION_A)) {
            // when
            List<BookingLockView> locks = endpoint.locks();

            // then
            assertThat(locks).singleElement().satisfies(lock -> {
                assertThat(lock.courtSessionId()).isEqualTo(SESSION_A);
                assertThat(lock.holders()).singleElement().asString().matches(HOLDER_FORMAT);
                assertThat(lock.ttlMs()).isPositive();
            });
        }
    }

    @Test
    void listsNothingOnceTheLockIsReleased() throws InterruptedException {
        // given
        HeldBookingLock held = HeldBookingLock.hold(redissonClient, SESSION_A);

        // when
        held.close();

        // then
        assertThat(endpoint.locks()).isEmpty();
    }

    @Test
    void listsEveryHeldLockOrderedBySession() throws InterruptedException {
        try (HeldBookingLock ignoredB = HeldBookingLock.hold(redissonClient, SESSION_B);
             HeldBookingLock ignoredA = HeldBookingLock.hold(redissonClient, SESSION_A)) {
            // when
            List<BookingLockView> locks = endpoint.locks();

            // then
            assertThat(locks).extracting(BookingLockView::courtSessionId).containsExactly(SESSION_A, SESSION_B);
        }
    }
}
