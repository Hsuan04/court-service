package com.courtservice.booking.config;

import com.courtservice.booking.strategy.BookingConcurrencyMode;
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
    void soldOutCacheDefaultsToDisabledWithFiveSecondTtl() {
        // given
        Binder binder = new Binder(new MapConfigurationPropertySource(
                Map.of(PREFIX + ".concurrency-mode", "synchronized")));

        // when
        BookingProperties properties = binder.bind(PREFIX, BookingProperties.class).get();

        // then
        assertThat(properties.soldOutCache().enabled()).isFalse();
        assertThat(properties.soldOutCache().ttl()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.soldOutCache().maximumSize()).isEqualTo(10_000);
    }

    @Test
    void soldOutCacheBindsConfiguredValues() {
        // given
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                PREFIX + ".concurrency-mode", "synchronized",
                PREFIX + ".sold-out-cache.enabled", "true",
                PREFIX + ".sold-out-cache.ttl", "3s",
                PREFIX + ".sold-out-cache.maximum-size", "500")));

        // when
        BookingProperties properties = binder.bind(PREFIX, BookingProperties.class).get();

        // then
        assertThat(properties.soldOutCache().enabled()).isTrue();
        assertThat(properties.soldOutCache().ttl()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.soldOutCache().maximumSize()).isEqualTo(500);
    }

    @Test
    void distributedLockDefaultsToThreeSecondWaitAndNoDebugHold() {
        // given
        Binder binder = new Binder(new MapConfigurationPropertySource(
                Map.of(PREFIX + ".concurrency-mode", "distributed-lock")));

        // when
        BookingProperties properties = binder.bind(PREFIX, BookingProperties.class).get();

        // then
        assertThat(properties.concurrencyMode()).isEqualTo(BookingConcurrencyMode.DISTRIBUTED_LOCK);
        assertThat(properties.distributedLock().waitTime()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.distributedLock().debugHold()).isZero();
    }

    @Test
    void distributedLockBindsConfiguredValues() {
        // given
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                PREFIX + ".concurrency-mode", "distributed-lock",
                PREFIX + ".distributed-lock.wait-time", "500ms",
                PREFIX + ".distributed-lock.debug-hold", "10s")));

        // when
        BookingProperties properties = binder.bind(PREFIX, BookingProperties.class).get();

        // then
        assertThat(properties.distributedLock().waitTime()).isEqualTo(Duration.ofMillis(500));
        assertThat(properties.distributedLock().debugHold()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void jvmLockDefaultsToThreeSecondWait() {
        // given
        Binder binder = new Binder(new MapConfigurationPropertySource(
                Map.of(PREFIX + ".concurrency-mode", "jvm-lock")));

        // when
        BookingProperties properties = binder.bind(PREFIX, BookingProperties.class).get();

        // then
        assertThat(properties.concurrencyMode()).isEqualTo(BookingConcurrencyMode.JVM_LOCK);
        assertThat(properties.jvmLock().waitTime()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void jvmLockBindsConfiguredWaitTime() {
        // given
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                PREFIX + ".concurrency-mode", "jvm-lock",
                PREFIX + ".jvm-lock.wait-time", "750ms")));

        // when
        BookingProperties properties = binder.bind(PREFIX, BookingProperties.class).get();

        // then
        assertThat(properties.jvmLock().waitTime()).isEqualTo(Duration.ofMillis(750));
    }

    @Test
    void optimisticRetryRejectsMaxBackoffShorterThanMinBackoff() {
        assertThatThrownBy(() -> new BookingProperties.OptimisticRetry(5, Duration.ofSeconds(5), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
