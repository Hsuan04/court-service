package com.courtservice;

import org.redisson.api.RedissonClient;

/**
 * Resets Redis between integration tests. {@link IntegrationTestData#truncateAll()} restarts ids,
 * so every test books {@code lock:court-session:1} again; a lock a previous test left behind would
 * be renewed by Redisson's watchdog for as long as its client lives and make the next test wait
 * until {@code LOCK_TIMEOUT}.
 *
 * <p>Public because the integration tests live in the feature packages they cover.
 */
public final class RedisTestData {

    private final RedissonClient redissonClient;

    public RedisTestData(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /** Removes every key, so each test starts from an empty Redis. */
    public void flushAll() {
        redissonClient.getKeys().flushall();
    }
}
