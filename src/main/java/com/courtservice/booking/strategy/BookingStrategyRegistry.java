package com.courtservice.booking.strategy;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.courtservice.booking.config.BookingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Picks the active {@link BookingStrategy} from all strategy beans,
 * based on {@link BookingProperties#concurrencyMode()}.
 * <p>
 * Fails fast at startup when two strategies claim the same mode, or when the
 * configured mode has no implementation yet.
 */
@Component
public class BookingStrategyRegistry {

    private static final Logger log = LoggerFactory.getLogger(BookingStrategyRegistry.class);

    private final BookingStrategy active;

    public BookingStrategyRegistry(List<BookingStrategy> strategies, BookingProperties properties) {
        Map<BookingConcurrencyMode, BookingStrategy> byMode = new EnumMap<>(BookingConcurrencyMode.class);
        for (BookingStrategy strategy : strategies) {
            BookingStrategy previous = byMode.put(strategy.mode(), strategy);
            if (previous != null) {
                throw new IllegalStateException("Duplicate booking strategies for mode " + strategy.mode()
                        + ": " + previous.getClass().getSimpleName()
                        + " and " + strategy.getClass().getSimpleName());
            }
        }

        BookingConcurrencyMode mode = properties.concurrencyMode();
        BookingStrategy selected = byMode.get(mode);
        if (selected == null) {
            throw new IllegalStateException("Booking concurrency mode " + mode
                    + " is not implemented yet; available: " + byMode.keySet());
        }

        this.active = selected;
        log.info("Booking concurrency strategy: {} ({})", mode, mode.description());
    }

    public BookingStrategy active() {
        return active;
    }
}
