package com.courtservice.booking.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for creating a booking.
 * @param courtSessionId     the court hosting the session
 * @param memberId the date of the session
 */
@Schema(description = "Request body for creating a booking.")
public record BookingCreateRequest (

    @Schema(description = "The id of the court session to book.", example = "1",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    Long courtSessionId,

    @Schema(description = "The id of the member making the booking.", example = "1",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    Long memberId){
}
