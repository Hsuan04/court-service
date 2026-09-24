package com.courtservice.session;

import com.courtservice.common.error.BusinessException;
import com.courtservice.common.error.BusinessRuleViolationException;
import com.courtservice.common.error.ErrorCode;
import com.courtservice.common.error.InvalidTimeRangeException;
import com.courtservice.court.Court;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CourtSessionTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 1);
    private static final LocalTime START = LocalTime.of(18, 0);
    private static final LocalTime END = LocalTime.of(20, 0);
    private static final OffsetDateTime OPEN_AT = OffsetDateTime.of(2026, 9, 25, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime CLOSE_AT = OPEN_AT.plusDays(5);
    private static final int CAPACITY = 10;

    @Test
    void constructorCreatesSessionWithNoBookings() {
        // when
        CourtSession session = newSession();

        // then
        assertThat(session.getCapacity()).isEqualTo(CAPACITY);
        assertThat(session.getBookedCount()).isZero();
    }

    @Test
    void constructorRejectsEndTimeNotAfterStartTime() {
        assertThatThrownBy(() -> new CourtSession(new Court("Court A", null), DATE, START, START,
                CAPACITY, OPEN_AT, CLOSE_AT))
                .isInstanceOf(InvalidTimeRangeException.class);
    }

    @Test
    void constructorRejectsCloseAtNotAfterOpenAt() {
        assertThatThrownBy(() -> new CourtSession(new Court("Court A", null), DATE, START, END,
                CAPACITY, OPEN_AT, OPEN_AT))
                .isInstanceOf(InvalidTimeRangeException.class);
    }

    @Test
    void constructorRejectsNonPositiveCapacity() {
        assertThatThrownBy(() -> new CourtSession(new Court("Court A", null), DATE, START, END,
                0, OPEN_AT, CLOSE_AT))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void rescheduleReplacesDateTimesAndBookingWindow() {
        // given
        CourtSession session = newSession();
        LocalDate newDate = DATE.plusDays(1);
        LocalTime newStart = LocalTime.of(8, 0);
        LocalTime newEnd = LocalTime.of(9, 30);
        OffsetDateTime newOpenAt = OPEN_AT.plusDays(1);
        OffsetDateTime newCloseAt = CLOSE_AT.plusDays(1);

        // when
        session.reschedule(newDate, newStart, newEnd, newOpenAt, newCloseAt);

        // then
        assertThat(session.getSessionDate()).isEqualTo(newDate);
        assertThat(session.getStartTime()).isEqualTo(newStart);
        assertThat(session.getEndTime()).isEqualTo(newEnd);
        assertThat(session.getOpenAt()).isEqualTo(newOpenAt);
        assertThat(session.getCloseAt()).isEqualTo(newCloseAt);
    }

    @Test
    void rescheduleRejectsEndTimeEqualToStartTime() {
        CourtSession session = newSession();

        assertThatThrownBy(() -> session.reschedule(DATE, START, START, OPEN_AT, CLOSE_AT))
                .isInstanceOf(InvalidTimeRangeException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_TIME_RANGE);
    }

    @Test
    void rescheduleRejectsEndTimeBeforeStartTime() {
        CourtSession session = newSession();

        assertThatThrownBy(() -> session.reschedule(DATE, END, START, OPEN_AT, CLOSE_AT))
                .isInstanceOf(InvalidTimeRangeException.class);
    }

    @Test
    void rescheduleRejectsCloseAtEqualToOpenAt() {
        CourtSession session = newSession();

        assertThatThrownBy(() -> session.reschedule(DATE, START, END, OPEN_AT, OPEN_AT))
                .isInstanceOf(InvalidTimeRangeException.class);
    }

    @Test
    void rescheduleRejectsCloseAtBeforeOpenAt() {
        CourtSession session = newSession();

        assertThatThrownBy(() -> session.reschedule(DATE, START, END, CLOSE_AT, OPEN_AT))
                .isInstanceOf(InvalidTimeRangeException.class);
    }

    @Test
    void rejectedRescheduleLeavesSessionUnchanged() {
        // given
        CourtSession session = newSession();

        // when
        assertThatThrownBy(() -> session.reschedule(DATE.plusDays(1), END, START, OPEN_AT, CLOSE_AT))
                .isInstanceOf(InvalidTimeRangeException.class);

        // then
        assertThat(session.getSessionDate()).isEqualTo(DATE);
        assertThat(session.getStartTime()).isEqualTo(START);
        assertThat(session.getEndTime()).isEqualTo(END);
    }

    @Test
    void changeCapacityUpdatesCapacity() {
        // given
        CourtSession session = newSession();

        // when
        session.changeCapacity(20);

        // then
        assertThat(session.getCapacity()).isEqualTo(20);
    }

    @Test
    void changeCapacityAllowsCapacityEqualToBookedCount() {
        // given
        CourtSession session = sessionWithBookings(5);

        // when
        session.changeCapacity(5);

        // then
        assertThat(session.getCapacity()).isEqualTo(5);
    }

    @Test
    void changeCapacityRejectsCapacityBelowBookedCount() {
        // given
        CourtSession session = sessionWithBookings(5);

        // when / then
        assertThatThrownBy(() -> session.changeCapacity(4))
                .isInstanceOf(BusinessRuleViolationException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.BUSINESS_RULE_VIOLATION);
        assertThat(session.getCapacity()).isEqualTo(CAPACITY);
    }

    @Test
    void changeCapacityRejectsZero() {
        CourtSession session = newSession();

        assertThatThrownBy(() -> session.changeCapacity(0))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void changeCapacityRejectsNegativeValue() {
        CourtSession session = newSession();

        assertThatThrownBy(() -> session.changeCapacity(-1))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void changeCapacityNeverChangesBookedCount() {
        // given
        CourtSession session = sessionWithBookings(3);

        // when
        session.changeCapacity(50);

        // then
        assertThat(session.getBookedCount()).isEqualTo(3);
    }

    private CourtSession newSession() {
        return new CourtSession(new Court("Court A", null), DATE, START, END, CAPACITY, OPEN_AT, CLOSE_AT);
    }

    /**
     * The entity deliberately has no mutator for bookedCount (only the booking flow may change
     * it), so the field is set reflectively to prepare the precondition.
     */
    private CourtSession sessionWithBookings(int bookedCount) {
        CourtSession session = newSession();
        ReflectionTestUtils.setField(session, "bookedCount", bookedCount);
        return session;
    }
}
