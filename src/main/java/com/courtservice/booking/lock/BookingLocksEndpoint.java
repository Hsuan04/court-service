package com.courtservice.booking.lock;

import org.redisson.api.RedissonClient;
import org.redisson.api.options.KeysScanOptions;
import org.redisson.api.options.MapOptions;
import org.redisson.client.codec.StringCodec;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Actuator endpoint {@code /actuator/bookinglocks} listing the booking locks currently held in
 * Redis, for watching the distributed-lock strategy at work.
 *
 * <p>The bean exists only when {@code app.booking.concurrency-mode} is {@code distributed-lock}.
 * The condition compares the raw property text, so the value must be written in that kebab-case
 * form; {@code DISTRIBUTED_LOCK} still selects the strategy but leaves this endpoint out. Even when
 * the bean exists, the endpoint is reachable over HTTP only where
 * {@code management.endpoints.web.exposure.include} lists it, which only the local profile does.
 *
 * <p>Keys are found with {@code SCAN}, never {@code KEYS}. Redis runs commands on a single thread,
 * and {@code KEYS} walks the whole keyspace in one command, blocking every other client, including
 * every booking taking or releasing a lock, until it finishes. {@code SCAN} walks it in small
 * batches between which other commands run. The price is that the result is not a snapshot: a
 * lock found by the scan can be released before it is read, so such keys are skipped.
 */
@Component
@Endpoint(id = "bookinglocks")
@ConditionalOnProperty(name = "app.booking.concurrency-mode", havingValue = "distributed-lock")
public class BookingLocksEndpoint {

    /** What {@code PTTL} returns for a key that no longer exists. */
    private static final long KEY_MISSING = -2;

    private final RedissonClient redissonClient;

    public BookingLocksEndpoint(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * Lists the booking locks held right now.
     *
     * @return one entry per held lock, ordered by session id; empty when no lock is held
     */
    @ReadOperation
    public List<BookingLockView> locks() {
        try (Stream<String> keys = redissonClient.getKeys()
                .getKeysStream(KeysScanOptions.defaults().pattern(BookingLockKeys.PATTERN))) {
            return keys.map(this::read)
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparing(BookingLockView::courtSessionId))
                    .toList();
        }
    }

    /** Reads one lock, or nothing if it was released after the scan found it. */
    private Optional<BookingLockView> read(String key) {
        // A Redisson lock is a hash whose fields are the holders (UUID:threadId) and whose values are
        // re-entry counts; only the field names are needed.
        List<String> holders = List.copyOf(redissonClient
                .getMap(MapOptions.<String, String>name(key).codec(StringCodec.INSTANCE))
                .readAllKeySet());
        long ttlMs = redissonClient.getKeys().remainTimeToLive(key);
        if (holders.isEmpty() || ttlMs == KEY_MISSING) {
            return Optional.empty();
        }
        return Optional.of(new BookingLockView(BookingLockKeys.sessionIdOf(key), holders, ttlMs));
    }
}
