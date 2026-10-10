package com.courtservice.booking.lock;

import java.util.List;

/**
 * One booking lock currently held in Redis, as listed by {@link BookingLocksEndpoint}.
 *
 * @param courtSessionId the session the lock protects
 * @param holders        the holders recorded in the lock, each {@code UUID:threadId}: the UUID
 *                       identifies the Redisson client (one per application instance) and the
 *                       thread id the thread inside it. Normally exactly one
 * @param ttlMs          the remaining time to live in milliseconds; the watchdog keeps renewing it
 *                       while the holder runs
 */
public record BookingLockView(Long courtSessionId, List<String> holders, long ttlMs) {
}
