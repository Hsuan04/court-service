package com.courtservice.session;

import com.courtservice.common.BaseEntity;
import com.courtservice.common.error.BusinessRuleViolationException;
import com.courtservice.common.error.InvalidTimeRangeException;
import com.courtservice.court.Court;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;

/**
 * A bookable time slot on a court, shared by many members up to {@code capacity}.
 *
 * <p>Time ranges and capacity are validated here rather than only by the database CHECK
 * constraints, so that violations surface as specific, client-correctable errors instead of
 * a generic constraint failure.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "court_session")
public class CourtSession extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "court_id", nullable = false)
    private Court court;

    @Column(nullable = false)
    private LocalDate sessionDate;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private LocalTime endTime;

    @Column(nullable = false)
    private int capacity;

    // Only the booking flow may change this; intentionally no public mutator.
    @Column(nullable = false)
    private int bookedCount;

    @Column(nullable = false)
    private OffsetDateTime openAt;

    @Column(nullable = false)
    private OffsetDateTime closeAt;

    @Version
    @Column(nullable = false)
    private Long version;

    /**
     * Creates a session with no bookings.
     *
     * @throws InvalidTimeRangeException      if {@code endTime} is not after {@code startTime}
     *                                        or {@code closeAt} is not after {@code openAt}
     * @throws BusinessRuleViolationException if {@code capacity} is not positive
     */
    public CourtSession(Court court, LocalDate sessionDate, LocalTime startTime, LocalTime endTime,
                        int capacity, OffsetDateTime openAt, OffsetDateTime closeAt) {
        validateTimeRanges(startTime, endTime, openAt, closeAt);
        validatePositive(capacity);
        this.court = court;
        this.sessionDate = sessionDate;
        this.startTime = startTime;
        this.endTime = endTime;
        this.capacity = capacity;
        this.bookedCount = 0;
        this.openAt = openAt;
        this.closeAt = closeAt;
    }

    /**
     * Moves this session to a new date, time slot and booking window.
     *
     * @param sessionDate the new session date
     * @param startTime   the new start time
     * @param endTime     the new end time, which must be after {@code startTime}
     * @param openAt      when booking opens
     * @param closeAt     when booking closes, which must be after {@code openAt}
     * @throws InvalidTimeRangeException if either time range is empty or reversed
     */
    public void reschedule(LocalDate sessionDate, LocalTime startTime, LocalTime endTime,
                           OffsetDateTime openAt, OffsetDateTime closeAt) {
        validateTimeRanges(startTime, endTime, openAt, closeAt);
        this.sessionDate = sessionDate;
        this.startTime = startTime;
        this.endTime = endTime;
        this.openAt = openAt;
        this.closeAt = closeAt;
    }

    /**
     * Changes the capacity of this session. Shrinking below the number of existing bookings is
     * rejected, because it would leave the session oversold.
     *
     * @param newCapacity the new capacity
     * @throws BusinessRuleViolationException if {@code newCapacity} is not positive or is less
     *                                        than {@code bookedCount}
     */
    public void changeCapacity(int newCapacity) {
        validatePositive(newCapacity);
        if (newCapacity < bookedCount) {
            throw new BusinessRuleViolationException(
                    "Capacity %d is less than the %d existing bookings.".formatted(newCapacity, bookedCount));
        }
        this.capacity = newCapacity;
    }

    private static void validateTimeRanges(LocalTime startTime, LocalTime endTime,
                                           OffsetDateTime openAt, OffsetDateTime closeAt) {
        if (!endTime.isAfter(startTime)) {
            throw new InvalidTimeRangeException("endTime must be after startTime.");
        }
        if (!closeAt.isAfter(openAt)) {
            throw new InvalidTimeRangeException("closeAt must be after openAt.");
        }
    }

    private static void validatePositive(int capacity) {
        if (capacity <= 0) {
            throw new BusinessRuleViolationException("Capacity must be greater than 0.");
        }
    }
}
