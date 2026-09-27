package com.courtservice.common.logging;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequestLoggingPathMatcherTest {

    private final RequestLoggingPathMatcher matcher = new RequestLoggingPathMatcher(List.of(
            "/api/health", "/actuator/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"));

    @Test
    void excludesExactPath() {
        assertThat(matcher.isExcluded("/api/health")).isTrue();
    }

    @Test
    void excludesPathsUnderWildcardPatterns() {
        assertThat(matcher.isExcluded("/actuator")).isTrue();
        assertThat(matcher.isExcluded("/actuator/health/db")).isTrue();
        assertThat(matcher.isExcluded("/v3/api-docs")).isTrue();
        assertThat(matcher.isExcluded("/v3/api-docs/swagger-config")).isTrue();
        assertThat(matcher.isExcluded("/swagger-ui/index.html")).isTrue();
        assertThat(matcher.isExcluded("/swagger-ui.html")).isTrue();
    }

    @Test
    void doesNotExcludeApiPaths() {
        assertThat(matcher.isExcluded("/api/courts")).isFalse();
        assertThat(matcher.isExcluded("/api/health/extra")).isFalse();
        assertThat(matcher.isExcluded("/api/court-sessions/1/bookings")).isFalse();
    }

    @Test
    void emptyListExcludesNothing() {
        assertThat(new RequestLoggingPathMatcher(List.of()).isExcluded("/api/health")).isFalse();
    }
}
