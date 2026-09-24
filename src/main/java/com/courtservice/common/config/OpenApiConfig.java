package com.courtservice.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI documentation configuration: API metadata and the shared Problem Details
 * schema that error responses across all endpoints reference.
 */
@Configuration
public class OpenApiConfig {

    private static final String PROBLEM_DETAIL_SCHEMA_NAME = "ProblemDetail";
    private static final String VALIDATION_ERROR_SCHEMA_NAME = "ValidationError";

    @Bean
    public OpenAPI courtServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Court Service API")
                        .version("1.0.0")
                        .description("API for booking court sessions with capacity-safe, "
                                + "high-concurrency booking guarantees."));
    }

    /**
     * Adds the shared Problem Details schema to the generated OpenAPI document. Registered
     * as a customizer, rather than directly on the {@link OpenAPI} bean, because springdoc
     * prunes component schemas that no operation references yet, and no endpoint exists yet
     * in this issue's scope to reference it from.
     */
    @Bean
    public GlobalOpenApiCustomizer problemDetailSchemaCustomizer() {
        return openApi -> openApi.getComponents()
                .addSchemas(VALIDATION_ERROR_SCHEMA_NAME, validationErrorSchema())
                .addSchemas(PROBLEM_DETAIL_SCHEMA_NAME, problemDetailSchema());
    }

    private Schema<?> validationErrorSchema() {
        return new ObjectSchema()
                .description("A single field-level validation failure.")
                .addProperty("field", new StringSchema().description("The rejected field.").example("name"))
                .addProperty("message", new StringSchema().description("Why the field was rejected.")
                        .example("must not be blank"))
                .addProperty("rejectedValue", new Schema<>().description("The rejected value.").example(""));
    }

    private Schema<?> problemDetailSchema() {
        return new ObjectSchema()
                .description("RFC 9457 Problem Details error response, shared by every endpoint.")
                .addProperty("type", new StringSchema().description("A URI identifying the error type.")
                        .example("https://court-service.example/problems/resource-not-found"))
                .addProperty("title", new StringSchema().description("A short, human-readable summary of the error.")
                        .example("Resource Not Found"))
                .addProperty("status", new IntegerSchema().description("The HTTP status code.").example(404))
                .addProperty("detail", new StringSchema().description("A human-readable explanation specific to this occurrence.")
                        .example("Court with id 99 was not found."))
                .addProperty("instance", new StringSchema().description("The request path that produced the error.")
                        .example("/api/courts/99"))
                .addProperty("code", new StringSchema().description("The machine-readable error code.")
                        .example("RESOURCE_NOT_FOUND"))
                .addProperty("timestamp", new StringSchema().description("UTC timestamp when the error occurred.")
                        .example("2026-09-24T06:30:15.123Z"))
                .addProperty("traceId", new StringSchema().description("The trace id correlating this error with server logs.")
                        .example("3fa85f64-5717-4562-b3fc-2c963f66afa6"))
                .addProperty("errors", new ArraySchema()
                        .description("Field-level validation failures. Present only when status is a validation error.")
                        .items(new Schema<>().$ref(VALIDATION_ERROR_SCHEMA_NAME)));
    }
}