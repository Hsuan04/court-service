package com.courtservice.booking;

import com.courtservice.booking.strategy.BookingProperties;
import com.courtservice.common.error.BusinessException;
import com.courtservice.common.error.ErrorCode;
import com.courtservice.session.CourtSessionChangedEvent;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Remembers, per session and in this instance's memory only, that a session is full, so later
 * bookings are rejected with {@code SESSION_FULL} before any strategy takes a lock or touches the
 * database. Configured by {@code app.booking.sold-out-cache.*} and disabled by default.
 *
 * <p>This is only a speed-up, never the correctness guarantee. Every instance has its own flags:
 * an instance that has not yet seen a session fill up still sends the request to the strategy,
 * whose database protection rejects it, so the flag can never cause an oversell.
 *
 * <p>Trade-offs:
 * <ul>
 *   <li>A flag lives until its TTL expires. A seat freed in the meantime (a raised capacity seen
 *       by another instance, or a future cancellation) is wrongly refused until then.</li>
 *   <li>Changing or deleting a session clears the flag on this instance after the change commits;
 *       other instances wait for the TTL. A cancellation flow must call {@link #evict(Long)}.</li>
 *   <li>A request that read the session just before such a change can set the flag again right
 *       after it was cleared; the TTL bounds how long that stale flag survives.</li>
 * </ul>
 */
@Slf4j
@Component
public class SoldOutSessionCache {

    /** Counts bookings rejected by a sold-out flag, i.e. requests that never reached a strategy. */
    public static final String FAST_REJECT_METRIC = "booking.sold_out.fast_reject";
    public static final String MODE_TAG = "mode";
    private static final String FAST_REJECT_DESCRIPTION =
            "Bookings rejected by the in-memory sold-out flag before reaching the booking strategy";

    private final boolean enabled;
    private final Cache<Long, Boolean> soldOutSessions;
    private final Counter fastRejects;

    /**
     * Creates the cache from {@code app.booking.sold-out-cache} using the system clock.
     *
     * @param properties    the booking settings
     * @param meterRegistry where the fast-reject counter is registered
     */
    @Autowired
    public SoldOutSessionCache(BookingProperties properties, MeterRegistry meterRegistry) {
        this(properties, meterRegistry, Ticker.systemTicker());
    }

    /**
     * The counter is tagged with the concurrency mode only. A session id tag would create one time
     * series per session ever booked, which grows without bound.
     */
    SoldOutSessionCache(BookingProperties properties, MeterRegistry meterRegistry, Ticker ticker) {
        BookingProperties.SoldOutCache settings = properties.soldOutCache();
        this.enabled = settings.enabled();
        this.soldOutSessions = Caffeine.newBuilder()
                .expireAfterWrite(settings.ttl())
                .maximumSize(settings.maximumSize())
                .ticker(ticker)
                .build();
        this.fastRejects = Counter.builder(FAST_REJECT_METRIC)
                .description(FAST_REJECT_DESCRIPTION)
                .tag(MODE_TAG, properties.concurrencyMode().name())
                .register(meterRegistry);
    }

    /**
     * Rejects a booking of a session flagged as sold out, without touching the database.
     *
     * <p>Because nothing is read, the checks a strategy would make first are skipped: an unknown
     * member gets {@code SESSION_FULL} instead of {@code RESOURCE_NOT_FOUND}, and a member who
     * already booked the session gets {@code SESSION_FULL} instead of {@code DUPLICATE_BOOKING}.
     * This is accepted because avoiding the database is the whole point of the flag, neither
     * request could succeed anyway, and the precise error returns once the flag expires or is
     * cleared.
     *
     * @param courtSessionId the session to be booked
     * @throws BusinessException with {@code SESSION_FULL} if the session is flagged as sold out
     */
    public void rejectIfSoldOut(Long courtSessionId) {
        if (isSoldOut(courtSessionId)) {
            fastRejects.increment();
            log.debug("Session {}: booking rejected by sold-out flag", courtSessionId);
            throw new BusinessException(ErrorCode.SESSION_FULL, ErrorCode.SESSION_FULL.getDefaultDetail());
        }
    }

    /**
     * Returns whether a session is currently flagged as sold out; always {@code false} when disabled.
     *
     * @param courtSessionId the session id
     * @return {@code true} if a live flag exists for the session
     */
    public boolean isSoldOut(Long courtSessionId) {
        return enabled && soldOutSessions.getIfPresent(courtSessionId) != null;
    }

    /**
     * Flags a session as sold out for the configured TTL, restarting the TTL if already flagged.
     * Does nothing when disabled.
     *
     * @param courtSessionId the session that was found full
     */
    public void markSoldOut(Long courtSessionId) {
        if (enabled && soldOutSessions.asMap().put(courtSessionId, Boolean.TRUE) == null) {
            log.info("Session {}: marked sold out on this instance", courtSessionId);
        }
    }

    /**
     * Clears the flag of a session, for example after a seat was freed by a cancellation.
     *
     * @param courtSessionId the session id
     */
    public void evict(Long courtSessionId) {
        soldOutSessions.invalidate(courtSessionId);
    }

    /** Clears every flag. */
    public void invalidateAll() {
        soldOutSessions.invalidateAll();
    }

    /**
     * Clears the flag of a session that was updated or deleted. Runs after commit: clearing earlier
     * would let a booking read the old, still committed capacity and set the flag again.
     *
     * @param event the change
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSessionChanged(CourtSessionChangedEvent event) {
        evict(event.courtSessionId());
    }
}
