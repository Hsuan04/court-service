package com.courtservice.booking.dto;

import com.courtservice.booking.BookingStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * A booking as returned by the API. Exposes only the ids of the session and the member so that
 * mapping never loads those entities.
 *
 * @param id             the booking id
 * @param courtSessionId the booked session
 * @param memberId       the member who booked
 * @param status         the booking status
 * @param createdAt      when the booking was created
 * @param updatedAt      when the booking was last updated
 */
@Schema(description = "A booking of a court session by a member.")
public record BookingResponse(

        @Schema(description = "The booking id.", example = "1", accessMode = Schema.AccessMode.READ_ONLY)
        Long id,

        @Schema(description = "The id of the booked court session.", example = "1")
        Long courtSessionId,

        @Schema(description = "The id of the member who booked.", example = "1")
        Long memberId,

        @Schema(description = "The booking status.", example = "CONFIRMED")
        BookingStatus status,

        @Schema(description = "When the booking was created.", example = "2026-09-24T06:30:15.123Z",
                accessMode = Schema.AccessMode.READ_ONLY)
        OffsetDateTime createdAt,

        @Schema(description = "When the booking was last updated.", example = "2026-09-24T06:30:15.123Z",
                accessMode = Schema.AccessMode.READ_ONLY)
        OffsetDateTime updatedAt) {
}
