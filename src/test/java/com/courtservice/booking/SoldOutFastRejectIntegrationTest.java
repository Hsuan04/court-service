package com.courtservice.booking;

import com.courtservice.IntegrationTestData;
import com.courtservice.TestcontainersConfiguration;
import com.courtservice.booking.cache.SoldOutSessionCache;
import com.courtservice.booking.dto.BookingCreateRequest;
import com.courtservice.booking.service.BookingService;
import com.courtservice.common.error.BusinessException;
import com.courtservice.common.error.ErrorCode;
import com.courtservice.session.CourtSessionService;
import com.courtservice.session.dto.CourtSessionUpdateRequest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the real {@link BookingService} with the sold-out flag enabled and a TTL long enough that no
 * flag expires during a test, so every case is deterministic.
 *
 * <p>The strategy is fixed to {@code pessimistic-lock} so these cases do not depend on the mode
 * chosen in {@code application-local.yaml}; the flag sits in front of every strategy alike.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "app.booking.concurrency-mode=pessimistic-lock",
        "app.booking.sold-out-cache.enabled=true",
        "app.booking.sold-out-cache.ttl=10m"
})
class SoldOutFastRejectIntegrationTest {

    private static final int CAPACITY = 10;
    private static final int SINGLE_SEAT = 1;
    private static final int MEMBERS = 500;
    private static final String SUCCESS = "SUCCESS";

    @Autowired
    private BookingService bookingService;

    @Autowired
    private SoldOutSessionCache soldOutSessionCache;

    @Autowired
    private CourtSessionService courtSessionService;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private IntegrationTestData data;
    private long courtId;

    @BeforeEach
    void setUp() {
        data = new IntegrationTestData(jdbcTemplate);
        data.truncateAll();
        // Truncation restarts ids, so a flag left by a previous test would hit this test's sessions.
        soldOutSessionCache.invalidateAll();
        courtId = data.insertCourt("Court A");
    }

    @Test
    void raceFillsTheSessionExactlyAndFlagsItSoldOut() throws InterruptedException {
        // given
        long sessionId = data.insertSession(courtId, CAPACITY);
        List<Long> memberIds = IntStream.range(0, MEMBERS)
                .mapToObj(i -> data.insertMember("Member " + i))
                .toList();

        // when
        Map<String, Integer> outcomes = race(sessionId, memberIds);

        // then
        assertThat(outcomes).containsExactlyInAnyOrderEntriesOf(Map.of(
                SUCCESS, CAPACITY,
                ErrorCode.SESSION_FULL.name(), MEMBERS - CAPACITY));
        assertThat(bookingRows(sessionId)).isEqualTo(CAPACITY);
        assertThat(data.bookedCount(sessionId)).isEqualTo(CAPACITY);
        assertThat(soldOutSessionCache.isSoldOut(sessionId)).isTrue();
    }

    /**
     * A seat freed where this instance cannot see it (another instance, or a direct database change)
     * is still refused while the flag lives: the accepted cost of never asking the database.
     */
    @Test
    void flagKeepsRejectingAfterASeatIsFreedElsewhereUntilEvicted() {
        // given
        long sessionId = data.insertSession(courtId, SINGLE_SEAT);
        bookingService.create(new BookingCreateRequest(sessionId, data.insertMember("Alice")));
        data.setBookedCount(sessionId, 0);
        long bob = data.insertMember("Bob");
        double fastRejectsBefore = fastRejects();

        // when / then
        assertSessionFull(sessionId, bob);
        assertThat(fastRejects() - fastRejectsBefore).isEqualTo(1);

        // when
        soldOutSessionCache.evict(sessionId);

        // then
        assertThat(bookingService.create(new BookingCreateRequest(sessionId, bob)).bookedCount())
                .isEqualTo(SINGLE_SEAT);
    }

    @Test
    void raisingTheCapacityClearsTheFlag() {
        // given
        long sessionId = data.insertSession(courtId, SINGLE_SEAT);
        bookingService.create(new BookingCreateRequest(sessionId, data.insertMember("Alice")));
        long bob = data.insertMember("Bob");
        assertSessionFull(sessionId, bob);

        // when
        courtSessionService.update(sessionId, updateRequestWithCapacity(SINGLE_SEAT + 1));

        // then
        assertThat(soldOutSessionCache.isSoldOut(sessionId)).isFalse();
        assertThat(bookingService.create(new BookingCreateRequest(sessionId, bob)).bookedCount())
                .isEqualTo(SINGLE_SEAT + 1);
    }

    @Test
    void deletingTheSessionClearsTheFlagSoBookingItIsNotFound() {
        // given
        long sessionId = data.insertSession(courtId, SINGLE_SEAT);
        soldOutSessionCache.markSoldOut(sessionId);
        long alice = data.insertMember("Alice");

        // when
        courtSessionService.delete(sessionId);

        // then
        assertThatThrownBy(() -> bookingService.create(new BookingCreateRequest(sessionId, alice)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    /**
     * Pins the accepted change of error precedence: while the flag lives, a member who already
     * booked the session gets {@code SESSION_FULL} rather than {@code DUPLICATE_BOOKING}, because
     * the flag answers before anything is read from the database.
     */
    @Test
    void repeatedBookingOfAFlaggedSessionIsRejectedAsSessionFull() {
        // given
        long sessionId = data.insertSession(courtId, SINGLE_SEAT);
        long alice = data.insertMember("Alice");
        bookingService.create(new BookingCreateRequest(sessionId, alice));

        // when / then
        assertSessionFull(sessionId, alice);
        assertThat(bookingRows(sessionId)).isEqualTo(1);
    }

    private void assertSessionFull(long sessionId, long memberId) {
        assertThatThrownBy(() -> bookingService.create(new BookingCreateRequest(sessionId, memberId)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SESSION_FULL);
    }

    private static CourtSessionUpdateRequest updateRequestWithCapacity(int capacity) {
        return new CourtSessionUpdateRequest(IntegrationTestData.SESSION_DATE, IntegrationTestData.START_TIME,
                IntegrationTestData.END_TIME, capacity, IntegrationTestData.OPEN_AT, IntegrationTestData.CLOSE_AT);
    }

    /**
     * Starts one booking per member at the same moment, on platform threads like a servlet
     * container's workers, and counts {@code SUCCESS}, the error code of a business rejection, or
     * the class of any other failure.
     */
    private Map<String, Integer> race(long sessionId, List<Long> memberIds) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(memberIds.size());
        CountDownLatch start = new CountDownLatch(1);
        Map<String, Integer> outcomes = new ConcurrentHashMap<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(memberIds.size())) {
            for (Long memberId : memberIds) {
                pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    outcomes.merge(book(sessionId, memberId), 1, Integer::sum);
                    return null;
                });
            }
            ready.await();
            start.countDown();
        }
        return outcomes;
    }

    private String book(long sessionId, Long memberId) {
        try {
            bookingService.create(new BookingCreateRequest(sessionId, memberId));
            return SUCCESS;
        } catch (BusinessException e) {
            return e.getErrorCode().name();
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName();
        }
    }

    private double fastRejects() {
        return meterRegistry.get(SoldOutSessionCache.FAST_REJECT_METRIC).counter().count();
    }

    private int bookingRows(long sessionId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM booking WHERE court_session_id = ?", Integer.class, sessionId);
    }
}
