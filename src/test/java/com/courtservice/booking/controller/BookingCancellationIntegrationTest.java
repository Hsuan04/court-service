package com.courtservice.booking.controller;

import com.courtservice.booking.cache.SoldOutSessionCache;
import com.courtservice.booking.entity.BookingStatus;
import com.courtservice.booking.service.BookingService;
import com.courtservice.booking.stock.SeatStockKeys;
import com.courtservice.booking.stock.SeatStockScripts;
import com.courtservice.common.error.BusinessException;
import com.courtservice.common.error.ErrorCode;
import com.courtservice.support.IntegrationTestData;
import com.courtservice.support.RedisTestData;
import com.courtservice.support.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>Purpose:</b> prove that {@code DELETE /api/bookings/{id}} cancels a booking exactly once,
 * gives its seat back to the session, to the Redis seat stock and to the sold-out flag, and lets
 * the member book the session again.
 *
 * <p>Bookings are created through {@code POST /api/bookings} with the strategy configured for the
 * test profile; cancellation does not depend on the strategy.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class BookingCancellationIntegrationTest {

    private static final String BOOKINGS = "/api/bookings";
    private static final String BOOKING = "/api/bookings/{id}";
    private static final int CAPACITY = 10;
    private static final int ONE_SEAT = 1;
    private static final int CONCURRENT_CANCELS = 10;
    private static final long MISSING_ID = 999_999L;
    private static final String SUCCESS = "SUCCESS";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private SoldOutSessionCache soldOutSessionCache;

    @Autowired
    private SeatStockScripts seatStockScripts;

    @Autowired
    private BookingService bookingService;

    private IntegrationTestData data;

    @BeforeEach
    void resetState() {
        data = new IntegrationTestData(jdbcTemplate);
        data.truncateAll();
        new RedisTestData(redissonClient).flushAll();
        soldOutSessionCache.invalidateAll();
    }

    @Test
    void cancellingReturnsNoContentKeepsTheBookingAsCancelledAndFreesTheSeat() throws Exception {
        // given
        long sessionId = data.insertSession(data.insertCourt("Court"), CAPACITY);
        long bookingId = book(sessionId, data.insertMember("Member"));

        // when / then
        mockMvc.perform(delete(BOOKING, bookingId))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        mockMvc.perform(get(BOOKING, bookingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(BookingStatus.CANCELLED.name()));
        assertThat(data.bookedCount(sessionId)).isZero();
    }

    @Test
    void cancellingAnUnknownBookingIsNotFound() throws Exception {
        mockMvc.perform(delete(BOOKING, MISSING_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(ErrorCode.RESOURCE_NOT_FOUND.name()));
    }

    @Test
    void cancellingWithAnInvalidIdIsABadRequest() throws Exception {
        mockMvc.perform(delete(BOOKING, "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAMETER.name()));
    }

    @Test
    void cancellingTwiceIsRejectedAndFreesTheSeatOnlyOnce() throws Exception {
        // given
        long sessionId = data.insertSession(data.insertCourt("Court"), CAPACITY);
        long bookingId = book(sessionId, data.insertMember("Member"));
        mockMvc.perform(delete(BOOKING, bookingId)).andExpect(status().isNoContent());

        // when / then
        mockMvc.perform(delete(BOOKING, bookingId))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(ErrorCode.BOOKING_ALREADY_CANCELLED.name()));
        assertThat(data.bookedCount(sessionId)).isZero();
    }

    /** The cancelled row stays, but only confirmed bookings are unique, so the member may book again. */
    @Test
    void memberCanBookTheSessionAgainAfterCancelling() throws Exception {
        // given
        long sessionId = data.insertSession(data.insertCourt("Court"), CAPACITY);
        long memberId = data.insertMember("Member");
        mockMvc.perform(delete(BOOKING, book(sessionId, memberId))).andExpect(status().isNoContent());

        // when
        book(sessionId, memberId);

        // then
        assertThat(data.bookedCount(sessionId)).isEqualTo(1);
        assertThat(bookingRows(sessionId, BookingStatus.CONFIRMED)).isEqualTo(1);
        assertThat(bookingRows(sessionId, BookingStatus.CANCELLED)).isEqualTo(1);
    }

    /**
     * A one-seat session becomes sold out when booked; cancelling clears the flag on this instance,
     * so the next member is not turned away by it.
     */
    @Test
    void cancellingClearsTheSoldOutFlag() throws Exception {
        // given
        long sessionId = data.insertSession(data.insertCourt("Court"), ONE_SEAT);
        long bookingId = book(sessionId, data.insertMember("First"));
        assertThat(soldOutSessionCache.isSoldOut(sessionId)).isTrue();

        // when
        mockMvc.perform(delete(BOOKING, bookingId)).andExpect(status().isNoContent());

        // then
        assertThat(soldOutSessionCache.isSoldOut(sessionId)).isFalse();
        book(sessionId, data.insertMember("Second"));
    }

    /** After commit, the seat goes back to an existing Redis stock and the member leaves its set. */
    @Test
    void cancellingGivesTheSeatBackToTheRedisSeatStock() throws Exception {
        // given: a booking and a seat stock that already counts it
        long sessionId = data.insertSession(data.insertCourt("Court"), CAPACITY);
        long memberId = data.insertMember("Member");
        long bookingId = book(sessionId, memberId);
        seatStockScripts.initialize(sessionId, CAPACITY - 1, List.of(memberId));

        // when
        mockMvc.perform(delete(BOOKING, bookingId)).andExpect(status().isNoContent());

        // then
        assertThat(redissonClient.<String>getBucket(SeatStockKeys.stock(sessionId), StringCodec.INSTANCE).get())
                .isEqualTo(Integer.toString(CAPACITY));
        assertThat(redissonClient.getSet(SeatStockKeys.members(sessionId), StringCodec.INSTANCE).readAll())
                .isEmpty();
    }

    /** Ten requests cancel the same booking at once: one succeeds, and the seat is freed once. */
    @Test
    void concurrentCancellationsOfOneBookingCancelItExactlyOnce() throws Exception {
        // given
        long sessionId = data.insertSession(data.insertCourt("Court"), CAPACITY);
        long bookingId = book(sessionId, data.insertMember("Member"));
        CountDownLatch ready = new CountDownLatch(CONCURRENT_CANCELS);
        CountDownLatch start = new CountDownLatch(1);
        Queue<String> outcomes = new ConcurrentLinkedQueue<>();

        // when
        try (ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_CANCELS)) {
            for (int i = 0; i < CONCURRENT_CANCELS; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    outcomes.add(cancel(bookingId));
                    return null;
                });
            }
            ready.await();
            start.countDown();
        }

        // then
        assertThat(outcomes.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting())))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        SUCCESS, 1L,
                        ErrorCode.BOOKING_ALREADY_CANCELLED.name(), (long) (CONCURRENT_CANCELS - 1)));
        assertThat(data.bookedCount(sessionId)).isZero();
    }

    /** Books through the API and returns the new booking's id. */
    private long book(long sessionId, long memberId) throws Exception {
        String body = """
                {"courtSessionId": %d, "memberId": %d}
                """.formatted(sessionId, memberId);
        String response = mockMvc.perform(post(BOOKINGS).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.booking.id")).longValue();
    }

    /** {@code SUCCESS}, the error code of a business rejection, or the class of any other failure. */
    private String cancel(long bookingId) {
        try {
            bookingService.cancel(bookingId);
            return SUCCESS;
        } catch (BusinessException e) {
            return e.getErrorCode().name();
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName();
        }
    }

    private int bookingRows(long sessionId, BookingStatus status) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM booking WHERE court_session_id = ? AND status = ?",
                Integer.class, sessionId, status.name());
    }
}
