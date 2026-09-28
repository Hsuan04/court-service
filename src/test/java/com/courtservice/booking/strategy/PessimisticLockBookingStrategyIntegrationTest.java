package com.courtservice.booking.strategy;

import com.courtservice.IntegrationTestData;
import com.courtservice.TestcontainersConfiguration;
import com.courtservice.booking.strategy.impl.PessimisticLockBookingStrategy;
import com.courtservice.common.error.BusinessException;
import com.courtservice.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves in the default build, and therefore in CI, that pessimistic locking never oversells.
 *
 * <p>Unlike the tagged simulation, the outcome of this race does not depend on thread timing: the
 * row lock serializes every booking of the session, so exactly {@code CAPACITY} members succeed and
 * every other member is told the session is full. Each number is therefore asserted exactly.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PessimisticLockBookingStrategyIntegrationTest {

    private static final int CAPACITY = 10;
    private static final int MEMBERS = 50;

    @Autowired
    private PessimisticLockBookingStrategy strategy;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private IntegrationTestData data;

    @BeforeEach
    void setUp() {
        data = new IntegrationTestData(jdbcTemplate);
        data.truncateAll();
    }

    @Test
    void exactlyCapacityMembersWinARaceForOneSession() throws InterruptedException {
        // given
        long sessionId = data.insertSession(data.insertCourt("Court A"), CAPACITY);
        List<Long> memberIds = IntStream.range(0, MEMBERS)
                .mapToObj(i -> data.insertMember("Member " + i))
                .toList();

        CountDownLatch ready = new CountDownLatch(MEMBERS);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger sessionFull = new AtomicInteger();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();

        // when: every thread waits at the start gate so that all bookings hit the session together
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Long memberId : memberIds) {
                pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        strategy.book(sessionId, memberId);
                        successes.incrementAndGet();
                    } catch (BusinessException e) {
                        if (e.getErrorCode() == ErrorCode.SESSION_FULL) {
                            sessionFull.incrementAndGet();
                        } else {
                            unexpected.add(e);
                        }
                    } catch (RuntimeException e) {
                        unexpected.add(e);
                    }
                    return null;
                });
            }
            ready.await();
            start.countDown();
        }

        // then
        assertThat(unexpected).as("unexpected exceptions").isEmpty();
        assertThat(successes.get()).as("successful bookings").isEqualTo(CAPACITY);
        assertThat(sessionFull.get()).as("rejected as full").isEqualTo(MEMBERS - CAPACITY);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM booking WHERE court_session_id = ?",
                Integer.class, sessionId)).as("booking rows").isEqualTo(CAPACITY);
        assertThat(data.bookedCount(sessionId)).as("booked_count").isEqualTo(CAPACITY);
    }
}
