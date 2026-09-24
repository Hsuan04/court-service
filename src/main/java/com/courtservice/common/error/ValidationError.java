package com.courtservice.common.error;

/**
 * A single field-level validation failure included in a Problem Details response.
 *
 * @param field         the name of the rejected field
 * @param message       a human-readable description of why the field was rejected
 * @param rejectedValue the value that was rejected, or {@code null} if unavailable
 */
public record ValidationError(String field, String message, Object rejectedValue) {
}