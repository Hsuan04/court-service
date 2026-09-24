package com.courtservice.common.health;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response body for the application health check endpoint.
 *
 * @param status    overall application status
 * @param service   the application name
 * @param version   the application build version, or {@code "unknown"} if unavailable
 * @param database  database component status
 * @param timestamp when this health check was evaluated, in UTC ISO-8601 format
 */
public record HealthResponse(

        @Schema(description = "Overall application status.", example = "UP", allowableValues = {"UP", "DOWN"})
        String status,

        @Schema(description = "The application name.", example = "court-service")
        String service,

        @Schema(description = "The application build version.", example = "0.0.1-SNAPSHOT")
        String version,

        @Schema(description = "Database component status.", example = "UP", allowableValues = {"UP", "DOWN"})
        String database,

        @Schema(description = "UTC timestamp when this health check was evaluated.", example = "2026-09-24T06:30:15.123Z")
        String timestamp) {
}