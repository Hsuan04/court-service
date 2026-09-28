package com.courtservice.booking.strategy;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Booking settings bound from {@code app.booking.*}.
 * @param concurrencyMode which {@link BookingStrategy} is active
 * @param optimisticRetry retry settings of the {@code optimistic-lock} strategy
 */
@Validated
@ConfigurationProperties("app.booking")
public record BookingProperties(
        @NotNull BookingConcurrencyMode concurrencyMode,
        @Valid @DefaultValue OptimisticRetry optimisticRetry) {

    /**
     * How the optimistic-lock strategy retries after a version conflict, bound from
     * @param maxAttempts total attempts, including the first one
     * @param minBackoff  shortest wait before a retry
     * @param maxBackoff  longest wait before a retry
     */
    public record OptimisticRetry(
            @Min(1) @DefaultValue("5") int maxAttempts,
            @NotNull @DefaultValue("1s") Duration minBackoff,
            @NotNull @DefaultValue("5s") Duration maxBackoff) {

        /**
         * @throws IllegalArgumentException if {@code maxBackoff} is shorter than {@code minBackoff}
         */
        public OptimisticRetry {
            if (minBackoff != null && maxBackoff != null && maxBackoff.compareTo(minBackoff) < 0) {
                throw new IllegalArgumentException(
                        "maxBackoff %s must not be shorter than minBackoff %s".formatted(maxBackoff, minBackoff));
            }
        }
    }
}
