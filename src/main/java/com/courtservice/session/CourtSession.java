package com.courtservice.session;

import com.courtservice.common.BaseEntity;
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

    public CourtSession(Court court, LocalDate sessionDate, LocalTime startTime, LocalTime endTime,
                        int capacity, OffsetDateTime openAt, OffsetDateTime closeAt) {
        this.court = court;
        this.sessionDate = sessionDate;
        this.startTime = startTime;
        this.endTime = endTime;
        this.capacity = capacity;
        this.bookedCount = 0;
        this.openAt = openAt;
        this.closeAt = closeAt;
    }
}
