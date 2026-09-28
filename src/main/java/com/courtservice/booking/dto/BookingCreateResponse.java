package com.courtservice.booking.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record BookingCreateResponse (

    @Schema(description = "The created booking.")
    BookingResponse booking,
    @Schema(description = "Capacity of the session.", example = "10")
    int capacity,
    @Schema(description = "Booked count written by the active concurrency strategy.", example = "1")
    int bookedCount) {
}
