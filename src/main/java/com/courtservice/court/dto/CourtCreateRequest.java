package com.courtservice.court.dto;

import com.courtservice.court.Court;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for creating a court.
 *
 * @param name    the court name
 * @param address the court address, optional
 */
@Schema(description = "Request body for creating a court.")
public record CourtCreateRequest(

        @Schema(description = "The court name.", example = "Court A", maxLength = Court.NAME_MAX_LENGTH,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = Court.NAME_MAX_LENGTH)
        String name,

        @Schema(description = "The court address.", example = "No. 1, Section 1, Xinyi Road, Taipei",
                maxLength = Court.ADDRESS_MAX_LENGTH, nullable = true)
        @Size(max = Court.ADDRESS_MAX_LENGTH)
        String address) {
}
