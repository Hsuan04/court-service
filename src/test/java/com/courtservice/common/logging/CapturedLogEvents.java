package com.courtservice.common.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Captures the logging events of a single Logback logger so tests can assert on level, message
 * and MDC directly instead of on formatted console text, which depends on the active pattern.
 *
 * <p>Events of child loggers are captured too through Logback additivity. Levels and the global
 * Logback configuration are left untouched; call {@link #close()} after each test to detach.
 */
public final class CapturedLogEvents implements AutoCloseable {

    private static final String TRACE_ID_KEY = "traceId";

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender;

    private CapturedLogEvents(Logger logger, ListAppender<ILoggingEvent> appender) {
        this.logger = logger;
        this.appender = appender;
    }

    /**
     * Attaches a capturing appender to the given logger.
     *
     * @param loggerName the logger to capture, for example a class or package name
     * @return the capture, to be closed after the test
     */
    public static CapturedLogEvents attach(String loggerName) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerName);
        ListAppender<ILoggingEvent> appender = new ListAppender<>() {
            @Override
            protected void append(ILoggingEvent event) {
                // Logback reads the MDC and formats the message lazily; fix both now, because the
                // request's traceId is removed from the MDC as soon as the request completes.
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
        return new CapturedLogEvents(logger, appender);
    }

    /**
     * Returns all captured events in the order they were logged.
     */
    public List<ILoggingEvent> all() {
        return List.copyOf(appender.list);
    }

    /**
     * Returns the captured events whose MDC carries the given trace id, in the order they were logged.
     *
     * @param traceId the expected {@code traceId} MDC value
     */
    public List<ILoggingEvent> withTraceId(String traceId) {
        return appender.list.stream()
                .filter(event -> traceId.equals(event.getMDCPropertyMap().get(TRACE_ID_KEY)))
                .toList();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
