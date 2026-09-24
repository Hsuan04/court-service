package com.courtservice.member.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * A member as returned by the API.
 *
 * @param id        the member id
 * @param name      the member name
 * @param createdAt when the member was created
 * @param updatedAt when the member was last updated
 */
@Schema(description = "A member.")
public record MemberResponse(

        @Schema(description = "The member id.", example = "1", accessMode = Schema.AccessMode.READ_ONLY)
        Long id,

        @Schema(description = "The member name.", example = "Alice")
        String name,

        @Schema(description = "When the member was created.", example = "2026-09-24T06:30:15.123Z",
                accessMode = Schema.AccessMode.READ_ONLY)
        OffsetDateTime createdAt,

        @Schema(description = "When the member was last updated.", example = "2026-09-24T06:30:15.123Z",
                accessMode = Schema.AccessMode.READ_ONLY)
        OffsetDateTime updatedAt) {
}
