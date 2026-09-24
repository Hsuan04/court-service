package com.courtservice.common.health;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * Exposes application and database health status for external monitoring, backed by
 * Spring Boot Actuator's {@link HealthEndpoint} so results stay consistent with
 * {@code /actuator/health}.
 */
@Tag(name = "Health", description = "Application health check")
@RestController
public class HealthController {

    private static final String DATABASE_COMPONENT_NAME = "db";
    private static final String UNKNOWN_VERSION = "unknown";

    private final HealthEndpoint healthEndpoint;
    private final ObjectProvider<BuildProperties> buildPropertiesProvider;
    private final String serviceName;

    public HealthController(HealthEndpoint healthEndpoint, ObjectProvider<BuildProperties> buildPropertiesProvider,
            @Value("${spring.application.name}") String serviceName) {
        this.healthEndpoint = healthEndpoint;
        this.buildPropertiesProvider = buildPropertiesProvider;
        this.serviceName = serviceName;
    }

    @Operation(summary = "Check application health",
            description = "Reports the overall application status and the database status, sourced from Spring Boot Actuator.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The application and database are healthy.",
                    content = @Content(schema = @Schema(implementation = HealthResponse.class))),
            @ApiResponse(responseCode = "503", description = "The application or the database is unhealthy.",
                    content = @Content(schema = @Schema(implementation = HealthResponse.class)))
    })
    @GetMapping("/api/health")
    public ResponseEntity<HealthResponse> checkHealth() {
        HealthDescriptor descriptor = healthEndpoint.health();
        Status overallStatus = descriptor.getStatus();
        Status databaseStatus = resolveDatabaseStatus(descriptor);

        HealthResponse response = new HealthResponse(
                overallStatus.getCode(),
                serviceName,
                resolveVersion(),
                databaseStatus.getCode(),
                DateTimeFormatter.ISO_INSTANT.format(Instant.now()));

        HttpStatus httpStatus = Status.UP.equals(overallStatus) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(httpStatus).body(response);
    }

    private Status resolveDatabaseStatus(HealthDescriptor descriptor) {
        if (descriptor instanceof CompositeHealthDescriptor composite) {
            HealthDescriptor database = composite.getComponents().get(DATABASE_COMPONENT_NAME);
            if (database != null) {
                return database.getStatus();
            }
        }
        return Status.UNKNOWN;
    }

    private String resolveVersion() {
        BuildProperties buildProperties = buildPropertiesProvider.getIfAvailable();
        return buildProperties != null ? buildProperties.getVersion() : UNKNOWN_VERSION;
    }
}