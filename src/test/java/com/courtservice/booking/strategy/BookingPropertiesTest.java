package com.courtservice.booking.strategy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookingPropertiesTest {

    private static final String PREFIX = "app.booking";

    @Test
    void optimisticRetryDefaultsToFiveAttemptsWaitingOneToFiveSeconds() {
        // given
        Binder binder = new Binder(new MapConfigurationPropertySource(
                Map.of(PREFIX + ".concurrency-mode", "optimistic-lock")));

        // when
        BookingProperties properties = binder.bind(PREFIX, BookingProperties.class).get();

        // then
        assertThat(properties.optimisticRetry().maxAttempts()).isEqualTo(5);
        assertThat(properties.optimisticRetry().minBackoff()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.optimisticRetry().maxBackoff()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void optimisticRetryBindsConfiguredValues() {
        // given
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                PREFIX + ".concurrency-mode", "optimistic-lock",
                PREFIX + ".optimistic-retry.max-attempts", "3",
                PREFIX + ".optimistic-retry.min-backoff", "200ms",
                PREFIX + ".optimistic-retry.max-backoff", "2s")));

        // when
        BookingProperties properties = binder.bind(PREFIX, BookingProperties.class).get();

        // then
        assertThat(properties.optimisticRetry().maxAttempts()).isEqualTo(3);
        assertThat(properties.optimisticRetry().minBackoff()).isEqualTo(Duration.ofMillis(200));
        assertThat(properties.optimisticRetry().maxBackoff()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void optimisticRetryRejectsMaxBackoffShorterThanMinBackoff() {
        assertThatThrownBy(() -> new BookingProperties.OptimisticRetry(5, Duration.ofSeconds(5), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
