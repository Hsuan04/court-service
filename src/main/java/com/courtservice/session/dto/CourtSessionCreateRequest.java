package com.courtservice.session.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;

/**
 * Request body for creating a court session. Deliberately has no {@code bookedCount} or
 * {@code version}: those are owned by the booking flow and by optimistic locking.
 *
 * @param courtId     the court hosting the session
 * @param sessionDate the date of the session
 * @param startTime   the start time
 * @param endTime     the end time, which must be after {@code startTime}
 * @param capacity    the maximum number of bookings
 * @param openAt      when booking opens
 * @param closeAt     when booking closes, which must be after {@code openAt}
 */
@Schema(description = "Request body for creating a court session.")
public record CourtSessionCreateRequest(

        @Schema(description = "The id of the court hosting the session.", example = "1",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        Long courtId,

        @Schema(description = "The date of the session.", example = "2026-10-01",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        LocalDate sessionDate,

        @Schema(description = "The start time of the session.", example = "18:00:00", type = "string",
                format = "time", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        LocalTime startTime,

        @Schema(description = "The end time of the session; must be after startTime.", example = "20:00:00",
                type = "string", format = "time", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        LocalTime endTime,

        @Schema(description = "The maximum number of bookings; must be greater than 0.", example = "100",
                minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        @Positive
        Integer capacity,

        @Schema(description = "When booking opens.", example = "2026-09-25T00:00:00Z",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        OffsetDateTime openAt,

        @Schema(description = "When booking closes; must be after openAt.", example = "2026-09-30T23:59:59Z",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        OffsetDateTime closeAt) {
}
