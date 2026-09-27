package com.courtservice.common.logging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for SQL execution logging, bound from {@code app.logging.sql}.
 *
 * @param enabled              whether the DataSource is proxied at all
 * @param logAllQueries        log every statement at DEBUG instead of only slow ones
 * @param slowQueryThresholdMs statements taking at least this long are logged at WARN
 * @param logParameters        include bind parameters; off by default because parameters may carry
 *                             personal data such as member names
 */
@ConfigurationProperties("app.logging.sql")
public record SqlLoggingProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("false") boolean logAllQueries,
        @DefaultValue("100") long slowQueryThresholdMs,
        @DefaultValue("false") boolean logParameters) {
}
