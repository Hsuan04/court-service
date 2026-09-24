package com.courtservice.common.error;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.net.URI;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorCodeTest {

    @Test
    void typeUriIsDerivedFromEnumNameInKebabCase() {
        assertThat(ErrorCode.RESOURCE_NOT_FOUND.getType())
                .isEqualTo(URI.create("https://court-service.example/problems/resource-not-found"));
        assertThat(ErrorCode.INVALID_TIME_RANGE.getType())
                .isEqualTo(URI.create("https://court-service.example/problems/invalid-time-range"));
    }

    @Test
    void everyErrorCodeHasAUniqueType() {
        long distinctTypes = Arrays.stream(ErrorCode.values())
                .map(ErrorCode::getType)
                .collect(Collectors.toSet())
                .size();

        assertThat(distinctTypes).isEqualTo(ErrorCode.values().length);
    }

    @Test
    void httpStatusMatchesDocumentedMapping() {
        assertThat(ErrorCode.VALIDATION_FAILED.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorCode.MALFORMED_REQUEST.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorCode.INVALID_PARAMETER.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorCode.INVALID_TIME_RANGE.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorCode.RESOURCE_NOT_FOUND.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.ENDPOINT_NOT_FOUND.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.METHOD_NOT_ALLOWED.getHttpStatus()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(ErrorCode.RESOURCE_IN_USE.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.BUSINESS_RULE_VIOLATION.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.CONCURRENT_MODIFICATION.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.INTERNAL_ERROR.getHttpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}