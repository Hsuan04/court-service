package com.courtservice.common.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the ECS structured format configured for the JSON profiles. The encoder runs on its
 * own {@link LoggerContext}, so the global Logback configuration used by other tests is never
 * touched.
 */
class EcsLogFormatTest {

    private static final String ECS_FORMAT = "ecs";

    @Test
    void ecsOutputIsSingleLineJsonWithRequiredFields() {
        // given
        LoggerContext context = new LoggerContext();
        Environment environment = new MockEnvironment().withProperty("spring.application.name", "court-service");
        context.putObject(Environment.class.getName(), environment);
        StructuredLogEncoder encoder = new StructuredLogEncoder();
        encoder.setContext(context);
        encoder.setFormat(ECS_FORMAT);
        encoder.start();

        LoggingEvent event = new LoggingEvent();
        event.setLoggerContext(context);
        event.setLevel(Level.INFO);
        event.setLoggerName("com.courtservice.common.logging.RequestLoggingInterceptor");
        event.setThreadName("http-nio-8080-exec-1");
        event.setMessage("<-- POST /api/courts 201 CourtController.create 18ms");
        event.setInstant(Instant.parse("2026-09-25T06:30:15.123Z"));
        event.setMDCPropertyMap(Map.of("traceId", "abc-123"));

        // when
        String output = new String(encoder.encode(event), StandardCharsets.UTF_8);
        encoder.stop();

        // then
        assertThat(output.strip().lines()).hasSize(1);
        JsonNode json = JsonMapper.builder().build().readTree(output);
        assertThat(json.path("@timestamp").asString()).isEqualTo("2026-09-25T06:30:15.123Z");
        assertThat(json.path("log").path("level").asString()).isEqualTo("INFO");
        assertThat(json.path("log").path("logger").asString())
                .isEqualTo("com.courtservice.common.logging.RequestLoggingInterceptor");
        assertThat(json.path("process").path("thread").path("name").asString()).isEqualTo("http-nio-8080-exec-1");
        assertThat(json.path("message").asString()).isEqualTo("<-- POST /api/courts 201 CourtController.create 18ms");
        assertThat(json.path("traceId").asString()).isEqualTo("abc-123");
        assertThat(json.path("service").path("name").asString()).isEqualTo("court-service");
    }
}
