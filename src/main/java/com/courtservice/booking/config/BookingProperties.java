package com.courtservice.booking.config;

import com.courtservice.booking.strategy.BookingConcurrencyMode;
import com.courtservice.booking.strategy.BookingStrategy;
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
 * @param soldOutCache    settings of the in-memory sold-out flag that rejects bookings of a full
 *                        session before any strategy runs
 * @param distributedLock settings of the {@code distributed-lock} strategy
 */
@Validated
@ConfigurationProperties("app.booking")
public record BookingProperties(
        @NotNull BookingConcurrencyMode concurrencyMode,
        @Valid @DefaultValue OptimisticRetry optimisticRetry,
        @Valid @DefaultValue SoldOutCache soldOutCache,
        @Valid @DefaultValue DistributedLock distributedLock) {

    /**
     * Settings of the distributed-lock strategy, bound from {@code app.booking.distributed-lock.*}.
     *
     * <p>There is deliberately no lease time: without one, Redisson's watchdog keeps renewing the
     * lock while its holder is alive. A fixed lease would let the lock expire under a booking that
     * runs longer than expected, and a second holder could then enter.
     *
     * @param waitTime  longest wait for a session's lock before the booking fails with
     *                  {@code LOCK_TIMEOUT}
     * @param debugHold how long to keep the lock after acquiring it and before the transaction
     *                  starts, so the lock can be inspected by hand. For local observation only:
     *                  never set it in production, where it would make every booking of a session
     *                  queue for this long. {@code 0} disables it
     */
    public record DistributedLock(
            @NotNull @DefaultValue("3s") Duration waitTime,
            @NotNull @DefaultValue("0s") Duration debugHold) {
    }

    /**
     * The per-instance sold-out flag, bound from {@code app.booking.sold-out-cache.*}. Disabled by
     * default so that each strategy can be observed on its own.
     *
     * @param enabled     whether full sessions are remembered and later bookings rejected early
     * @param ttl         how long a flag lives; a longer TTL rejects more requests early but may
     *                    wrongly reject a booking after a seat was freed elsewhere
     * @param maximumSize the most sessions flagged at once; beyond it Caffeine drops flags, which
     *                    only costs those sessions a trip to the database
     */
    public record SoldOutCache(
            @DefaultValue("false") boolean enabled,
            @NotNull @DefaultValue("5s") Duration ttl,
            @Min(1) @DefaultValue("10000") long maximumSize) {
    }

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
