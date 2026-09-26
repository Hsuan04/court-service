package com.courtservice.common.logging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Settings for API request logging, bound from {@code app.logging.request}.
 *
 * @param enabled            whether request logging components are registered at all
 * @param excludedPaths      path patterns (Spring {@code PathPattern} syntax) that are never logged
 * @param maskedFields       field names, matched case-insensitively, whose values are replaced by {@code ***}
 * @param maxPayloadLength   maximum length of a serialized payload before it is truncated
 */
@ConfigurationProperties("app.logging.request")
public record RequestLoggingProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue({"/api/health", "/actuator/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"})
        List<String> excludedPaths,
        @DefaultValue({"password", "token", "secret", "authorization"}) List<String> maskedFields,
        @DefaultValue("1000") int maxPayloadLength) {
}
