package com.courtservice.booking.cache;

import com.courtservice.booking.config.BookingProperties;
import com.courtservice.booking.strategy.BookingConcurrencyMode;
import com.courtservice.common.error.BusinessException;
import com.courtservice.common.error.ErrorCode;
import com.courtservice.session.CourtSessionChangedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives expiry with a manual clock, so the TTL is asserted without sleeping.
 */
class SoldOutSessionCacheTest {

    private static final long SESSION_ID = 1L;
    private static final long OTHER_SESSION_ID = 2L;
    private static final Duration TTL = Duration.ofSeconds(5);
    private static final long MAXIMUM_SIZE = 100;
    private static final BookingConcurrencyMode MODE = BookingConcurrencyMode.SYNCHRONIZED;

    private final AtomicLong nanos = new AtomicLong();
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
    }

    @Test
    void markedSessionIsSoldOutAndOthersAreNot() {
        // given
        SoldOutSessionCache cache = cache(true);

        // when
        cache.markSoldOut(SESSION_ID);

        // then
        assertThat(cache.isSoldOut(SESSION_ID)).isTrue();
        assertThat(cache.isSoldOut(OTHER_SESSION_ID)).isFalse();
    }

    @Test
    void flagExpiresAfterTheTtl() {
        // given
        SoldOutSessionCache cache = cache(true);
        cache.markSoldOut(SESSION_ID);

        // when
        nanos.addAndGet(TTL.toNanos());

        // then
        assertThat(cache.isSoldOut(SESSION_ID)).isFalse();
    }

    @Test
    void flagSurvivesUntilJustBeforeTheTtl() {
        // given
        SoldOutSessionCache cache = cache(true);
        cache.markSoldOut(SESSION_ID);

        // when
        nanos.addAndGet(TTL.toNanos() - 1);

        // then
        assertThat(cache.isSoldOut(SESSION_ID)).isTrue();
    }

    @Test
    void evictClearsTheFlag() {
        // given
        SoldOutSessionCache cache = cache(true);
        cache.markSoldOut(SESSION_ID);

        // when
        cache.evict(SESSION_ID);

        // then
        assertThat(cache.isSoldOut(SESSION_ID)).isFalse();
    }

    @Test
    void sessionChangeClearsTheFlag() {
        // given
        SoldOutSessionCache cache = cache(true);
        cache.markSoldOut(SESSION_ID);

        // when
        cache.onSessionChanged(new CourtSessionChangedEvent(SESSION_ID));

        // then
        assertThat(cache.isSoldOut(SESSION_ID)).isFalse();
    }

    @Test
    void disabledCacheNeverReportsSoldOut() {
        // given
        SoldOutSessionCache cache = cache(false);

        // when
        cache.markSoldOut(SESSION_ID);

        // then
        assertThat(cache.isSoldOut(SESSION_ID)).isFalse();
        assertThatCode(() -> cache.rejectIfSoldOut(SESSION_ID)).doesNotThrowAnyException();
    }

    @Test
    void rejectingAFlaggedSessionThrowsSessionFullAndCountsIt() {
        // given
        SoldOutSessionCache cache = cache(true);
        cache.markSoldOut(SESSION_ID);

        // when / then
        assertThatThrownBy(() -> cache.rejectIfSoldOut(SESSION_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SESSION_FULL);
        assertThat(fastRejectCounter().count()).isEqualTo(1);
    }

    @Test
    void unflaggedSessionPassesWithoutCounting() {
        // given
        SoldOutSessionCache cache = cache(true);

        // when / then
        assertThatCode(() -> cache.rejectIfSoldOut(SESSION_ID)).doesNotThrowAnyException();
        assertThat(fastRejectCounter().count()).isZero();
    }

    @Test
    void fastRejectCounterIsTaggedWithTheModeOnly() {
        // given
        cache(true);

        // when
        Counter counter = fastRejectCounter();

        // then
        assertThat(counter.getId().getTags())
                .singleElement()
                .satisfies(tag -> {
                    assertThat(tag.getKey()).isEqualTo(SoldOutSessionCache.MODE_TAG);
                    assertThat(tag.getValue()).isEqualTo(MODE.name());
                });
    }

    private SoldOutSessionCache cache(boolean enabled) {
        BookingProperties properties = new BookingProperties(MODE,
                new BookingProperties.OptimisticRetry(1, Duration.ZERO, Duration.ZERO),
                new BookingProperties.SoldOutCache(enabled, TTL, MAXIMUM_SIZE),
                new BookingProperties.DistributedLock(Duration.ZERO, Duration.ZERO));
        return new SoldOutSessionCache(properties, meterRegistry, nanos::get);
    }

    private Counter fastRejectCounter() {
        return meterRegistry.get(SoldOutSessionCache.FAST_REJECT_METRIC).counter();
    }
}
