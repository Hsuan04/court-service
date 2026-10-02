package com.courtservice.booking.concurrency;

import com.courtservice.booking.dto.BookingCreateRequest;
import com.courtservice.booking.service.BookingService;
import com.courtservice.booking.strategy.BookingConcurrencyMode;
import com.courtservice.common.error.BusinessException;
import com.courtservice.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>Purpose:</b> prove that the atomic-update strategy (one conditional
 * {@code UPDATE ... SET booked_count = booked_count + 1 WHERE booked_count < capacity}) never
 * oversells, never books a member twice and keeps {@code booked_count} in step with the booking
 * rows under contention.
 *
 * <p><b>Steps</b> (see {@link BookingRaceIntegrationTestBase}): create a court, create a session,
 * create the members, then let every member book at the same moment.
 *
 * <p><b>Expected:</b> the database applies the check and the increment as one statement, so no
 * two bookings can take the same seat; every number is asserted exactly.
 */
class AtomicUpdateBookingRaceIntegrationTest extends BookingRaceIntegrationTestBase {

    private static final int SAME_MEMBER_REQUESTS = 20;
    private static final int ONE_SEAT = 1;
    private static final long MISSING_SESSION_ID = 999_999L;

    private BookingService bookingService;

    @BeforeEach
    void useStrategy() {
        bookingService = bookingServiceUsing(strategy(BookingConcurrencyMode.ATOMIC_UPDATE));
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
        logReport("ATOMIC_UPDATE: " + MEMBERS + " members, " + CAPACITY + " seats", result, sessionId, CAPACITY);
        assertFilledExactly(result, sessionId, MEMBERS, CAPACITY);
    }

    /**
     * One member sends 20 requests at once: each takes a seat with the conditional update before
     * its insert, and the unique constraint rejects all inserts but one ({@code DUPLICATE_BOOKING}).
     * {@code booked_count} 1 proves that the seats taken by the rejected requests were rolled back.
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

    /** Booking a full session is rejected without writing anything. */
    @Test
    void bookingAFullSessionIsRejectedWithoutWriting() {
        // given: a one-seat session already taken
        long sessionId = createSession(createCourt(), ONE_SEAT);
        List<Long> members = createMembers(2);
        bookingService.create(new BookingCreateRequest(sessionId, members.get(0)));

        // when / then
        assertThatThrownBy(() -> bookingService.create(new BookingCreateRequest(sessionId, members.get(1))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SESSION_FULL);
        assertThat(bookingRows(sessionId)).isEqualTo(1);
        assertThat(bookedCount(sessionId)).isEqualTo(1);
    }

    /** Booking a session that does not exist is reported as not found. */
    @Test
    void bookingAnUnknownSessionIsNotFound() {
        // given
        long memberId = createMembers(1).getFirst();

        // when / then
        assertThatThrownBy(() -> bookingService.create(new BookingCreateRequest(MISSING_SESSION_ID, memberId)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }
}
