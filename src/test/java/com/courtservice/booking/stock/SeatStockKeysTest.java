package com.courtservice.booking.stock;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SeatStockKeysTest {

    private static final long SESSION_ID = 42L;
    private static final String HASH_TAG = "{court-session:42}";

    @Test
    void stockAndMembersKeysAreNamedPerSession() {
        assertThat(SeatStockKeys.stock(SESSION_ID)).isEqualTo("booking:{court-session:42}:stock");
        assertThat(SeatStockKeys.members(SESSION_ID)).isEqualTo("booking:{court-session:42}:members");
    }

    /** Redis Cluster hashes only the tag, so keys sharing it can be used by one Lua script. */
    @Test
    void bothKeysOfASessionShareOneHashTag() {
        assertThat(SeatStockKeys.stock(SESSION_ID)).contains(HASH_TAG);
        assertThat(SeatStockKeys.members(SESSION_ID)).contains(HASH_TAG);
    }
}
