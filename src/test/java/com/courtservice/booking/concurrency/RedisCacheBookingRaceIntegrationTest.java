package com.courtservice.booking.concurrency;

import com.courtservice.booking.dto.BookingCreateRequest;
import com.courtservice.booking.service.BookingService;
import com.courtservice.booking.stock.SeatStockKeys;
import com.courtservice.booking.strategy.BookingConcurrencyMode;
import com.courtservice.booking.strategy.impl.RedisCacheBookingStrategy;
import com.courtservice.common.config.RedisConnectionProperties;
import com.courtservice.common.error.BusinessException;
import com.courtservice.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>Purpose:</b> prove that the redis-cache strategy (a seat counter in Redis pre-deducted with
 * {@code DECR}, then a conditional database update) never oversells, also across application
 * instances, and that its counter stays in step with the database: initialized from it, given a
 * seat back when the booking fails, and adjusted when the capacity changes.
 *
 * <p><b>Steps</b> (see {@link BookingRaceIntegrationTestBase}): create a court, create the session,
 * create the members, then let the members book at the same moment.
 *
 * <p><b>Expected:</b> races are exact, and after each test the counter in Redis equals
 * {@code capacity - booked_count}.
 */
class RedisCacheBookingRaceIntegrationTest extends BookingRaceIntegrationTestBase {

    private static final int SAME_MEMBER_REQUESTS = 20;
    private static final int EARLIER_BOOKINGS = 3;
    private static final int TWO_SEATS = 2;
    private static final int FOUR_SEATS = 4;
    private static final int ONE_SEAT = 1;
    private static final long MISSING_ID = 999_999L;
    private static final int REBOOKING_MEMBERS = 50;

    @Autowired
    private RedisConnectionProperties redisConnectionProperties;

    private BookingService bookingService;

    @BeforeEach
    void useStrategy() {
        bookingService = bookingServiceUsing(strategy(BookingConcurrencyMode.REDIS_CACHE));
    }

    /** 500 members race for 10 seats: exactly 10 win and the counter ends at 0. */
    @Test
    void exactlyCapacityMembersWinTheRace() throws InterruptedException {
        // given: steps 1 to 3
        long sessionId = createSession(createCourt(), CAPACITY);
        List<Long> memberIds = createMembers(MEMBERS);

        // when: step 4
        RaceResult result = race(everyMemberBooks(bookingService, sessionId, memberIds));

        // then
        logReport("REDIS_CACHE: " + MEMBERS + " members, " + CAPACITY + " seats", result, sessionId, CAPACITY);
        assertFilledExactly(result, sessionId, MEMBERS, CAPACITY);
        assertThat(seatStock(sessionId)).isZero();
    }

    /**
     * 500 members race for 10 seats, alternating between two instances. Each instance has its own
     * Redisson client, so they share the seat counter only through Redis.
     */
    @Test
    void twoInstancesSharingTheStockFillTheSessionExactly() throws InterruptedException {
        // given: steps 1 to 3, plus a second instance
        long sessionId = createSession(createCourt(), CAPACITY);
        List<Long> memberIds = createMembers(MEMBERS);
        RedissonClient secondInstanceClient = newRedissonClient();
        try {
            BookingService secondInstance = bookingServiceUsing(new RedisCacheBookingStrategy(courtSessionService,
                    memberService, bookingRepository, transactionTemplate, secondInstanceClient));
            List<BookingRequest> requests = new ArrayList<>(memberIds.size());
            for (int i = 0; i < memberIds.size(); i++) {
                requests.add(new BookingRequest(i % 2 == 0 ? bookingService : secondInstance, sessionId,
                        memberIds.get(i)));
            }

            // when: step 4, members alternate between the two instances
            RaceResult result = race(requests);

            // then
            logReport("REDIS_CACHE, two instances", result, sessionId, CAPACITY);
            assertFilledExactly(result, sessionId, MEMBERS, CAPACITY);
            assertThat(seatStock(sessionId)).isZero();
        } finally {
            secondInstanceClient.shutdown();
        }
    }

    /**
     * One member sends 20 requests at once: exactly one booking, the rest are duplicates, and every
     * seat the duplicates took from the counter is given back.
     *
     * <p>The session has a seat for each request on purpose. The counter does not know members, so
     * every duplicate takes a seat until the database rejects it; with fewer seats, some requests
     * would find the counter empty and get {@code SESSION_FULL} instead, a number that depends on
     * timing. Rejecting duplicates before they take a seat is what {@code redis-lua} adds.
     */
    @Test
    void sameMemberRacingWithItselfBooksOnlyOnce() throws InterruptedException {
        // given
        long sessionId = createSession(createCourt(), SAME_MEMBER_REQUESTS);
        List<Long> sameMemberTwentyTimes = Collections.nCopies(SAME_MEMBER_REQUESTS, createMembers(1).getFirst());

        // when
        RaceResult result = race(everyMemberBooks(bookingService, sessionId, sameMemberTwentyTimes));

        // then
        assertThat(result.outcomesOf(sessionId)).containsExactlyInAnyOrderEntriesOf(Map.of(
                SUCCESS, 1L,
                ErrorCode.DUPLICATE_BOOKING.name(), (long) (SAME_MEMBER_REQUESTS - 1)));
        assertThat(bookingRows(sessionId)).isEqualTo(1);
        assertThat(bookedCount(sessionId)).isEqualTo(1);
        assertThat(seatStock(sessionId)).isEqualTo(SAME_MEMBER_REQUESTS - 1);
    }

    /** A booking by an unknown member fails with 404 and gives its seat back to the counter. */
    @Test
    void unknownMemberGivesTheSeatBack() {
        // given
        long sessionId = createSession(createCourt(), CAPACITY);

        // when / then
        assertErrorCode(() -> bookingService.create(new BookingCreateRequest(sessionId, MISSING_ID)),
                ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(seatStock(sessionId)).isEqualTo(CAPACITY);
        assertThat(bookedCount(sessionId)).isZero();
    }

    /** A booking of an unknown session fails with 404 before any counter is created. */
    @Test
    void unknownSessionIsRejectedWithoutCreatingStock() {
        // given
        long memberId = createMembers(1).getFirst();

        // when / then
        assertErrorCode(() -> bookingService.create(new BookingCreateRequest(MISSING_ID, memberId)),
                ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(redissonClient.getBucket(SeatStockKeys.stock(MISSING_ID)).isExists()).isFalse();
    }

    /**
     * Bookings made before the counter existed (here through atomic-update) are counted when the
     * first redis-cache booking initializes it from the database.
     */
    @Test
    void stockIsInitializedFromExistingBookings() {
        // given: three seats taken before Redis knew the session
        long sessionId = createSession(createCourt(), CAPACITY);
        List<Long> memberIds = createMembers(EARLIER_BOOKINGS + 1);
        BookingService earlierService = bookingServiceUsing(strategy(BookingConcurrencyMode.ATOMIC_UPDATE));
        memberIds.subList(0, EARLIER_BOOKINGS)
                .forEach(memberId -> earlierService.create(new BookingCreateRequest(sessionId, memberId)));

        // when
        bookingService.create(new BookingCreateRequest(sessionId, memberIds.getLast()));

        // then
        assertThat(bookedCount(sessionId)).isEqualTo(EARLIER_BOOKINGS + 1);
        assertThat(seatStock(sessionId)).isEqualTo(CAPACITY - EARLIER_BOOKINGS - 1);
    }

    /** After a full session's capacity is raised by two, exactly two more members get a seat. */
    @Test
    void raisingCapacityAddsSeatsToTheStock() {
        // given: a two-seat session filled through redis-cache
        long sessionId = createSession(createCourt(), TWO_SEATS);
        List<Long> memberIds = createMembers(FOUR_SEATS + 1);
        memberIds.subList(0, TWO_SEATS)
                .forEach(memberId -> bookingService.create(new BookingCreateRequest(sessionId, memberId)));
        assertErrorCode(() -> bookingService.create(new BookingCreateRequest(sessionId, memberIds.get(TWO_SEATS))),
                ErrorCode.SESSION_FULL);

        // when
        changeCapacity(sessionId, FOUR_SEATS);

        // then
        assertThat(seatStock(sessionId)).isEqualTo(FOUR_SEATS - TWO_SEATS);
        memberIds.subList(TWO_SEATS, FOUR_SEATS)
                .forEach(memberId -> bookingService.create(new BookingCreateRequest(sessionId, memberId)));
        assertErrorCode(() -> bookingService.create(new BookingCreateRequest(sessionId, memberIds.getLast())),
                ErrorCode.SESSION_FULL);
        assertThat(bookedCount(sessionId)).isEqualTo(FOUR_SEATS);
        assertThat(seatStock(sessionId)).isZero();
    }

    /**
     * When the counter has drifted above the database, the database still refuses the extra seat,
     * and the seat is not given back: that would push the counter further from the truth.
     */
    @Test
    void seatGrantedByRedisButRefusedByTheDatabaseIsNotGivenBack() {
        // given: a one-seat session already taken, with the counter wrongly showing one seat left
        long sessionId = createSession(createCourt(), ONE_SEAT);
        List<Long> memberIds = createMembers(2);
        bookingService.create(new BookingCreateRequest(sessionId, memberIds.getFirst()));
        stockBucket(sessionId).set(Integer.toString(ONE_SEAT));

        // when / then
        assertErrorCode(() -> bookingService.create(new BookingCreateRequest(sessionId, memberIds.getLast())),
                ErrorCode.SESSION_FULL);
        assertThat(bookingRows(sessionId)).isEqualTo(ONE_SEAT);
        assertThat(seatStock(sessionId)).isZero();
    }

    /**
     * In a full one-seat session the only booking is cancelled, then 50 new members race for the
     * freed seat: exactly one gets it, because the cancellation gave the seat back to the stock once.
     */
    @Test
    void cancelledSeatIsRebookedExactlyOnce() throws InterruptedException {
        // given: a full one-seat session whose only booking is then cancelled
        long sessionId = createSession(createCourt(), ONE_SEAT);
        long firstMember = createMembers(1).getFirst();
        cancel(bookingService.create(new BookingCreateRequest(sessionId, firstMember)).booking().id());
        assertThat(seatStock(sessionId)).isEqualTo(ONE_SEAT);
        List<Long> racers = createMembers(REBOOKING_MEMBERS);

        // when
        RaceResult result = race(everyMemberBooks(bookingService, sessionId, racers));

        // then
        assertThat(result.outcomesOf(sessionId)).containsExactlyInAnyOrderEntriesOf(Map.of(
                SUCCESS, 1L,
                ErrorCode.SESSION_FULL.name(), (long) (REBOOKING_MEMBERS - 1)));
        assertThat(confirmedBookingRows(sessionId)).isEqualTo(ONE_SEAT);
        assertThat(bookedCount(sessionId)).isEqualTo(ONE_SEAT);
        assertThat(seatStock(sessionId)).isZero();
    }

    private long seatStock(long sessionId) {
        return Long.parseLong(stockBucket(sessionId).get());
    }

    private RBucket<String> stockBucket(long sessionId) {
        return redissonClient.getBucket(SeatStockKeys.stock(sessionId), StringCodec.INSTANCE);
    }

    private static void assertErrorCode(Runnable booking, ErrorCode expected) {
        assertThatThrownBy(booking::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
    }

    /** A second Redisson client, standing in for a second application instance. */
    private RedissonClient newRedissonClient() {
        Config config = new Config();
        config.useSingleServer().setAddress(redisConnectionProperties.address());
        return Redisson.create(config);
    }
}
