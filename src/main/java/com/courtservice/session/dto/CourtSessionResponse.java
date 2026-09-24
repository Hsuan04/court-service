package com.courtservice.session.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;

/**
 * A court session as returned by the API.
 *
 * @param id          the session id
 * @param courtId     the court hosting the session
 * @param sessionDate the date of the session
 * @param startTime   the start time
 * @param endTime     the end time
 * @param capacity    the maximum number of bookings
 * @param bookedCount the current number of bookings; read-only, changed only by the booking flow
 * @param openAt      when booking opens
 * @param closeAt     when booking closes
 * @param createdAt   when the session was created
 * @param updatedAt   when the session was last updated
 */
@Schema(description = "A court session.")
public record CourtSessionResponse(

        @Schema(description = "The session id.", example = "1", accessMode = Schema.AccessMode.READ_ONLY)
        Long id,

        @Schema(description = "The id of the court hosting the session.", example = "1")
        Long courtId,

        @Schema(description = "The date of the session.", example = "2026-10-01")
        LocalDate sessionDate,

        @Schema(description = "The start time of the session.", example = "18:00:00", type = "string", format = "time")
        LocalTime startTime,

        @Schema(description = "The end time of the session.", example = "20:00:00", type = "string", format = "time")
        LocalTime endTime,

        @Schema(description = "The maximum number of bookings.", example = "100")
        int capacity,

        @Schema(description = "The current number of bookings. Read-only: it is changed only by the booking "
                + "flow and is ignored if sent in a request.", example = "0", accessMode = Schema.AccessMode.READ_ONLY)
        int bookedCount,

        @Schema(description = "When booking opens.", example = "2026-09-25T00:00:00Z")
        OffsetDateTime openAt,

        @Schema(description = "When booking closes.", example = "2026-09-30T23:59:59Z")
        OffsetDateTime closeAt,

        @Schema(description = "When the session was created.", example = "2026-09-24T06:30:15.123Z",
                accessMode = Schema.AccessMode.READ_ONLY)
        OffsetDateTime createdAt,

        @Schema(description = "When the session was last updated.", example = "2026-09-24T06:30:15.123Z",
                accessMode = Schema.AccessMode.READ_ONLY)
        OffsetDateTime updatedAt) {
}
