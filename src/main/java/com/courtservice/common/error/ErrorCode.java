package com.courtservice.common.error;

import org.springframework.http.HttpStatus;

import java.net.URI;
import java.util.Locale;

/**
 * Catalog of all expected error conditions the API can return.
 *
 * <p>Each constant carries the HTTP status, a human-readable title and a default
 * detail message. The Problem Details {@code type} URI is derived from the
 * constant name so it never needs to be repeated or hand-typed per value.
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation Failed",
            "The request contains invalid fields."),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Malformed Request",
            "The request body could not be read."),
    INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "Invalid Parameter",
            "A request parameter has an invalid value."),
    INVALID_TIME_RANGE(HttpStatus.BAD_REQUEST, "Invalid Time Range",
            "The given time range is invalid."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Resource Not Found",
            "The requested resource was not found."),
    ENDPOINT_NOT_FOUND(HttpStatus.NOT_FOUND, "Endpoint Not Found",
            "The requested endpoint does not exist."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Method Not Allowed",
            "The HTTP method is not supported for this endpoint."),
    RESOURCE_IN_USE(HttpStatus.CONFLICT, "Resource In Use",
            "The resource cannot be modified because it is referenced elsewhere."),
    BUSINESS_RULE_VIOLATION(HttpStatus.CONFLICT, "Business Rule Violation",
            "The request violates a business rule."),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "Concurrent Modification",
            "The resource was modified concurrently. Please retry."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
            "An unexpected error occurred. Please contact support with the trace ID.");

    private static final String PROBLEM_TYPE_BASE_URI = "https://court-service.example/problems/";

    private final HttpStatus httpStatus;
    private final String title;
    private final String defaultDetail;

    ErrorCode(HttpStatus httpStatus, String title, String defaultDetail) {
        this.httpStatus = httpStatus;
        this.title = title;
        this.defaultDetail = defaultDetail;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getTitle() {
        return title;
    }

    public String getDefaultDetail() {
        return defaultDetail;
    }

    /**
     * Returns the Problem Details {@code type} URI for this error code, derived from its
     * name (e.g. {@code RESOURCE_NOT_FOUND} becomes {@code .../problems/resource-not-found}).
     */
    public URI getType() {
        String kebabCase = name().toLowerCase(Locale.ROOT).replace('_', '-');
        return URI.create(PROBLEM_TYPE_BASE_URI + kebabCase);
    }
}