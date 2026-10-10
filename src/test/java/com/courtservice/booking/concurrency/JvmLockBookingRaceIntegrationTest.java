package com.courtservice.booking.concurrency;

import com.courtservice.booking.config.BookingProperties;
import com.courtservice.booking.service.BookingService;
import com.courtservice.booking.strategy.BookingConcurrencyMode;
import com.courtservice.booking.strategy.impl.JvmLockBookingStrategy;
import com.courtservice.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>Purpose:</b> prove that the jvm-lock strategy (one {@code ReentrantLock} per session, held
 * around the whole transaction) never oversells and never books a member twice, and that sessions
 * do not share a lock.
 *
 * <p><b>Steps</b> (see {@link BookingRaceIntegrationTestBase}): create a court, create the
 * session(s), create the members, then let the members book at the same moment.
 *
 * <p><b>Expected:</b> races are exact. They run with a 30-second wait time so that no request
 * times out: with the default 3 seconds, 500 requests queuing on one lock could reach
 * {@code LOCK_TIMEOUT} on a slow machine. Timeouts are covered by the unit test.
 */
class JvmLockBookingRaceIntegrationTest extends BookingRaceIntegrationTestBase {

    private static final Duration RACE_WAIT = Duration.ofSeconds(30);
    private static final int SAME_MEMBER_REQUESTS = 20;

    private BookingService bookingService;

    @BeforeEach
    void useStrategy() {
        BookingProperties properties = new BookingProperties(BookingConcurrencyMode.JVM_LOCK,
                bookingProperties.optimisticRetry(), bookingProperties.soldOutCache(),
                bookingProperties.distributedLock(), new BookingProperties.JvmLock(RACE_WAIT));
        bookingService = bookingServiceUsing(new JvmLockBookingStrategy(courtSessionService, memberService,
                bookingRepository, transactionTemplate, properties));
    }

    /** 500 members race for 10 seats: exactly 10 win, 490 are told the session is full. */
    @Test
    void exactlyCapacityMembersWinTheRace() throws InterruptedException {
        // given: steps 1 to 3
        long sessionId = createSession(createCourt(), CAPACITY);
        List<Long> memberIds = createMembers(MEMBERS);

        // when: step 4
        RaceResult result = race(everyMemberBooks(bookingService, sessionId, memberIds));

        // then
        logReport("JVM_LOCK: " + MEMBERS + " members, " + CAPACITY + " seats", result, sessionId, CAPACITY);
        assertFilledExactly(result, sessionId, MEMBERS, CAPACITY);
    }

    /**
     * One member sends 20 requests at once: the lock serializes them, so the duplicate check sees
     * the first booking and rejects the other 19 before they write anything.
     */
    @Test
    void sameMemberRacingWithItselfBooksOnlyOnce() throws InterruptedException {
        // given
        long sessionId = createSession(createCourt(), CAPACITY);
        List<Long> sameMemberTwentyTimes = Collections.nCopies(SAME_MEMBER_REQUESTS, createMembers(1).getFirst());

        // when
        RaceResult result = race(everyMemberBooks(bookingService, sessionId, sameMemberTwentyTimes));

        // then
        assertThat(result.outcomesOf(sessionId)).containsExactlyInAnyOrderEntriesOf(Map.of(
                SUCCESS, 1L,
                ErrorCode.DUPLICATE_BOOKING.name(), (long) (SAME_MEMBER_REQUESTS - 1)));
        assertThat(bookingRows(sessionId)).isEqualTo(1);
        assertThat(bookedCount(sessionId)).isEqualTo(1);
    }

    /** Two sessions race at the same time, each behind its own lock, and both fill exactly. */
    @Test
    void twoSessionsFillExactlyBehindTheirOwnLocks() throws InterruptedException {
        // given: steps 1 to 3, with two sessions and two groups of members
        long courtId = createCourt();
        long sessionOne = createSession(courtId, CAPACITY);
        long sessionTwo = createSession(courtId, CAPACITY);
        List<Long> groupOne = createMembers(MEMBERS);
        List<Long> groupTwo = createMembers(MEMBERS);

        // when: step 4, both groups at once
        RaceResult result = race(Stream.concat(
                everyMemberBooks(bookingService, sessionOne, groupOne).stream(),
                everyMemberBooks(bookingService, sessionTwo, groupTwo).stream()).toList());

        // then
        assertFilledExactly(result, sessionOne, MEMBERS, CAPACITY);
        assertFilledExactly(result, sessionTwo, MEMBERS, CAPACITY);
    }
}
