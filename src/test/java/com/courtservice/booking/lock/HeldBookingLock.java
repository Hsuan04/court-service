package com.courtservice.booking.lock;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.CountDownLatch;

/**
 * Holds a session's booking lock on a thread of its own until closed, standing in for a booking in
 * progress elsewhere. An {@link RLock} must be released by the thread that took it, and is
 * re-entrant for that thread, so both happen on a dedicated thread rather than the test's.
 *
 * <p>Public because the tests that use it live in several packages.
 */
public final class HeldBookingLock implements AutoCloseable {

    private final CountDownLatch release = new CountDownLatch(1);
    private final Thread thread;

    private HeldBookingLock(RedissonClient redissonClient, long sessionId, CountDownLatch acquired) {
        this.thread = Thread.ofPlatform().start(() -> {
            RLock lock = redissonClient.getLock(BookingLockKeys.forSession(sessionId));
            lock.lock();
            try {
                acquired.countDown();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        });
    }

    /**
     * Takes the lock of a session on a new thread and returns once it is held.
     *
     * @param redissonClient the client to take the lock with
     * @param sessionId      the session whose lock to hold
     * @return the held lock, to be closed to release it
     * @throws InterruptedException if interrupted while waiting for the lock to be taken
     */
    public static HeldBookingLock hold(RedissonClient redissonClient, long sessionId) throws InterruptedException {
        CountDownLatch acquired = new CountDownLatch(1);
        HeldBookingLock held = new HeldBookingLock(redissonClient, sessionId, acquired);
        acquired.await();
        return held;
    }

    /** Releases the lock on its own thread and waits until it is released. */
    @Override
    public void close() throws InterruptedException {
        release.countDown();
        thread.join();
    }
}
