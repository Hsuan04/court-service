package com.courtservice.booking.lock;

/**
 * Names the Redis keys of the distributed booking locks, one per court session, so the strategy
 * that takes the locks and the endpoint that lists them agree on the format.
 */
public final class BookingLockKeys {

    /** Every booking lock key starts with this prefix, followed by the session id. */
    public static final String PREFIX = "lock:court-session:";

    /** Matches every booking lock key in a {@code SCAN}. */
    public static final String PATTERN = PREFIX + "*";

    private BookingLockKeys() {
    }

    /**
     * Returns the lock key of a court session.
     *
     * @param courtSessionId the session id
     * @return the key, for example {@code lock:court-session:42}
     */
    public static String forSession(Long courtSessionId) {
        return PREFIX + courtSessionId;
    }

    /**
     * Returns the session id a lock key belongs to.
     *
     * @param key a key produced by {@link #forSession(Long)}
     * @return the session id
     * @throws IllegalArgumentException if the key is not a booking lock key
     */
    public static Long sessionIdOf(String key) {
        if (!key.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Not a booking lock key: " + key);
        }
        return Long.valueOf(key.substring(PREFIX.length()));
    }
}
