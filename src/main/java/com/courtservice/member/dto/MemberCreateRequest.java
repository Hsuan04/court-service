package com.courtservice.member.dto;

import com.courtservice.member.Member;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for creating a member.
 *
 * @param name the member name
 */
@Schema(description = "Request body for creating a member.")
public record MemberCreateRequest(

        @Schema(description = "The member name.", example = "Alice", maxLength = Member.NAME_MAX_LENGTH,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = Member.NAME_MAX_LENGTH)
        String name) {
}
