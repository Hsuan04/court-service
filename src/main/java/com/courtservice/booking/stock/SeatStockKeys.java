package com.courtservice.booking.stock;

/**
 * Names the Redis keys that hold the seat stock of a court session for the Redis booking
 * strategies.
 *
 * <p>Both keys of a session share the hash tag {@code {court-session:<id>}}. On Redis Cluster only
 * the tag is hashed, so both keys land in the same slot, which a Lua script that touches both
 * requires.
 */
public final class SeatStockKeys {

    private static final String PREFIX = "booking:{court-session:";
    private static final String STOCK_SUFFIX = "}:stock";
    private static final String MEMBERS_SUFFIX = "}:members";

    private SeatStockKeys() {
    }

    /**
     * Returns the key holding the number of seats left in a session.
     *
     * @param courtSessionId the session id
     * @return the key, for example {@code booking:{court-session:42}:stock}
     */
    public static String stock(Long courtSessionId) {
        return PREFIX + courtSessionId + STOCK_SUFFIX;
    }

    /**
     * Returns the key holding the set of member ids that hold a seat in a session.
     *
     * @param courtSessionId the session id
     * @return the key, for example {@code booking:{court-session:42}:members}
     */
    public static String members(Long courtSessionId) {
        return PREFIX + courtSessionId + MEMBERS_SUFFIX;
    }
}
