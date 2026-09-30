package com.courtservice.booking.strategy;

import com.courtservice.IntegrationTestData;
import com.courtservice.TestcontainersConfiguration;
import com.courtservice.booking.BookingRepository;
import com.courtservice.booking.BookingService;
import com.courtservice.booking.dto.BookingCreateRequest;
import com.courtservice.common.error.BusinessException;
import com.courtservice.common.error.ErrorCode;
import com.courtservice.member.MemberService;
import com.courtservice.session.CourtSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
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
 * Proves in the default build, and therefore in CI, that the database-locking strategies never
 * oversell, never book a member twice and never leave {@code booked_count} out of step with the
 * booking rows. Their outcome does not depend on thread timing, so every number is asserted exactly.
 *
 * <p>Every case runs through {@link BookingService#create}, because that is where a unique
 * constraint violation becomes {@code DUPLICATE_BOOKING}. The application's {@code BookingService}
 * only uses the strategy configured in {@code app.booking.concurrency-mode}, so each case builds its
 * own from the real beans and a registry holding the strategy under test. Nothing is mocked, and
 * skipping the Spring proxy changes nothing: {@code create} runs without a transaction
 * ({@code NOT_SUPPORTED}) and every strategy opens its own.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LockingBookingStrategiesIntegrationTest {

    private static final int CAPACITY = 10;
    private static final int MEMBERS = 50;
    private static final int SAME_MEMBER_REQUESTS = 20;
    private static final String SUCCESS = "SUCCESS";

    @Autowired
    private List<BookingStrategy> strategies;

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
    private long sessionId;

    @BeforeEach
    void setUp() {
        data = new IntegrationTestData(jdbcTemplate);
        data.truncateAll();
        sessionId = data.insertSession(data.insertCourt("Court A"), CAPACITY);
    }

    @ParameterizedTest
    @EnumSource(value = BookingConcurrencyMode.class, names = {"PESSIMISTIC_LOCK", "ATOMIC_UPDATE"})
    void exactlyCapacityMembersWinARaceForOneSession(BookingConcurrencyMode mode) throws InterruptedException {
        // given
        List<Long> memberIds = IntStream.range(0, MEMBERS)
                .mapToObj(i -> data.insertMember("Member " + i))
                .toList();

        // when
        Map<String, Integer> outcomes = race(bookingServiceUsing(mode), memberIds);

        // then
        assertThat(outcomes).containsExactlyInAnyOrderEntriesOf(Map.of(
                SUCCESS, CAPACITY,
                ErrorCode.SESSION_FULL.name(), MEMBERS - CAPACITY));
        assertThat(bookingRows()).isEqualTo(CAPACITY);
        assertThat(data.bookedCount(sessionId)).isEqualTo(CAPACITY);
    }

    /**
     * Concurrent requests of one member can all pass the duplicate check before any of them
     * commits. Under atomic update each of them then takes a seat before the unique constraint
     * rejects its insert, so {@code booked_count = 1} proves that those seats were rolled back.
     */
    @ParameterizedTest
    @EnumSource(value = BookingConcurrencyMode.class, names = {"PESSIMISTIC_LOCK", "ATOMIC_UPDATE"})
    void sameMemberRacingWithItselfBooksOnlyOnce(BookingConcurrencyMode mode) throws InterruptedException {
        // given
        List<Long> sameMemberTwentyTimes = Collections.nCopies(SAME_MEMBER_REQUESTS, data.insertMember("Alice"));

        // when
        Map<String, Integer> outcomes = race(bookingServiceUsing(mode), sameMemberTwentyTimes);

        // then
        assertThat(outcomes).containsExactlyInAnyOrderEntriesOf(Map.of(
                SUCCESS, 1,
                ErrorCode.DUPLICATE_BOOKING.name(), SAME_MEMBER_REQUESTS - 1));
        assertThat(bookingRows()).isEqualTo(1);
        assertThat(data.bookedCount(sessionId)).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = BookingConcurrencyMode.class, names = {"PESSIMISTIC_LOCK", "ATOMIC_UPDATE"})
    void bookingAnUnknownSessionIsRejectedAsNotFound(BookingConcurrencyMode mode) {
        // given
        long memberId = data.insertMember("Alice");
        BookingService bookingService = bookingServiceUsing(mode);

        // when / then
        assertThatThrownBy(() -> bookingService.create(
                new BookingCreateRequest(IntegrationTestData.MISSING_ID, memberId)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @ParameterizedTest
    @EnumSource(value = BookingConcurrencyMode.class, names = {"PESSIMISTIC_LOCK", "ATOMIC_UPDATE"})
    void bookingAFullSessionIsRejectedWithoutWritingAnything(BookingConcurrencyMode mode) {
        // given
        data.setBookedCount(sessionId, CAPACITY);
        long memberId = data.insertMember("Alice");
        BookingService bookingService = bookingServiceUsing(mode);

        // when / then
        assertThatThrownBy(() -> bookingService.create(new BookingCreateRequest(sessionId, memberId)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SESSION_FULL);
        assertThat(bookingRows()).isZero();
        assertThat(data.bookedCount(sessionId)).isEqualTo(CAPACITY);
    }

    private BookingService bookingServiceUsing(BookingConcurrencyMode mode) {
        BookingStrategy strategy = strategies.stream()
                .filter(candidate -> candidate.mode() == mode)
                .findFirst()
                .orElseThrow();
        BookingStrategyRegistry registry = new BookingStrategyRegistry(List.of(strategy),
                new BookingProperties(mode, bookingProperties.optimisticRetry()));
        return new BookingService(bookingRepository, courtSessionService, memberService, registry);
    }

    /**
     * Starts one booking per entry of {@code memberIds} at the same moment and counts the results:
     * {@code SUCCESS}, the error code of a business rejection, or the class of any other failure.
     */
    private Map<String, Integer> race(BookingService bookingService, List<Long> memberIds)
            throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(memberIds.size());
        CountDownLatch start = new CountDownLatch(1);
        Map<String, Integer> outcomes = new ConcurrentHashMap<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Long memberId : memberIds) {
                pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    outcomes.merge(book(bookingService, memberId), 1, Integer::sum);
                    return null;
                });
            }
            ready.await();
            start.countDown();
        }
        return outcomes;
    }

    private String book(BookingService bookingService, Long memberId) {
        try {
            bookingService.create(new BookingCreateRequest(sessionId, memberId));
            return SUCCESS;
        } catch (BusinessException e) {
            return e.getErrorCode().name();
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName();
        }
    }

    private int bookingRows() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM booking WHERE court_session_id = ?", Integer.class, sessionId);
    }
}
