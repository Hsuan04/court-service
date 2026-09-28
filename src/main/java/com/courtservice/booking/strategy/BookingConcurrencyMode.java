package com.courtservice.booking.strategy;

/**
 * The booking concurrency strategies that {@code app.booking.concurrency-mode} can select. A mode
 * listed here may not be implemented yet; selecting one without a {@link BookingStrategy} bean makes
 * the application fail at startup.
 */
public enum BookingConcurrencyMode {
    NAIVE("Read-check-write with no concurrency control (intentionally unsafe)"),
    SYNCHRONIZED("One JVM-wide lock; every booking queues on the same lock"),
    JVM_LOCK("One ReentrantLock per session; only same-session bookings queue"),
    PESSIMISTIC_LOCK("SELECT ... FOR UPDATE on the session row"),
    OPTIMISTIC_LOCK("@Version check on update, retried in a new transaction"),
    ATOMIC_UPDATE("Single conditional UPDATE: booked_count < capacity"),
    REDIS_CACHE("Redis DECR pre-deducts seats before writing to the database"),
    REDIS_LUA("Lua script atomically checks and deducts in Redis");

    private final String description;

    BookingConcurrencyMode(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
