package com.courtservice.court.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * A court as returned by the API.
 *
 * @param id        the court id
 * @param name      the court name
 * @param address   the court address, or {@code null} if not set
 * @param createdAt when the court was created
 * @param updatedAt when the court was last updated
 */
@Schema(description = "A court.")
public record CourtResponse(

        @Schema(description = "The court id.", example = "1", accessMode = Schema.AccessMode.READ_ONLY)
        Long id,

        @Schema(description = "The court name.", example = "Court A")
        String name,

        @Schema(description = "The court address.", example = "No. 1, Section 1, Xinyi Road, Taipei", nullable = true)
        String address,

        @Schema(description = "When the court was created.", example = "2026-09-24T06:30:15.123Z",
                accessMode = Schema.AccessMode.READ_ONLY)
        OffsetDateTime createdAt,

        @Schema(description = "When the court was last updated.", example = "2026-09-24T06:30:15.123Z",
                accessMode = Schema.AccessMode.READ_ONLY)
        OffsetDateTime updatedAt) {
}
