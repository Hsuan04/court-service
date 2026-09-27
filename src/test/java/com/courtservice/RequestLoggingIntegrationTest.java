package com.courtservice;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.courtservice.common.logging.CapturedLogEvents;
import com.courtservice.common.logging.RequestLoggingAspect;
import com.courtservice.common.logging.RequestLoggingInterceptor;
import loggingsupport.TestLoggingControllerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies request logging end to end under the local profile, where the logging package is at
 * DEBUG. Each test sends its own X-Request-Id and inspects only the logging events carrying that
 * trace id. Assertions use the events' level, message and MDC rather than console text, so they
 * do not depend on the log pattern.
 */
@Import({TestcontainersConfiguration.class, TestLoggingControllerConfig.class})
@SpringBootTest
@AutoConfigureMockMvc
class RequestLoggingIntegrationTest {

    private static final String LOGGING_PACKAGE = "com.courtservice.common.logging";
    private static final Set<String> REQUEST_LOGGERS = Set.of(
            RequestLoggingInterceptor.class.getName(), RequestLoggingAspect.class.getName());

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LoggingSystem loggingSystem;

    private IntegrationTestData data;

    private CapturedLogEvents logs;

    private String traceId;

    @BeforeEach
    void setUp() {
        data = new IntegrationTestData(jdbcTemplate);
        data.truncateAll();
        traceId = "log-test-" + UUID.randomUUID();
        logs = CapturedLogEvents.attach(LOGGING_PACKAGE);
    }

    @AfterEach
    void detachLogs() {
        logs.close();
    }

    @Test
    void successfulCallLogsEntryAndExitAtInfoWithStatusDurationAndTraceId() throws Exception {
        // when
        createCourt();

        // then
        List<ILoggingEvent> infoEvents = requestLogEvents().stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .toList();
        assertThat(infoEvents).hasSize(2);
        assertThat(infoEvents.get(0).getFormattedMessage()).isEqualTo("--> POST /api/courts CourtController.create");
        assertThat(infoEvents.get(1).getFormattedMessage())
                .startsWith("<-- POST /api/courts 201 CourtController.create ")
                .containsPattern(" \\d+ms$");
    }

    @Test
    void debugLogsArgumentsAndResult() throws Exception {
        // when
        createCourt();

        // then
        List<ILoggingEvent> events = requestLogEvents();
        assertThat(events).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getFormattedMessage())
                    .contains("args: path={}, query={}, body={\"name\":\"Court A\",\"address\":\"Taipei\"}");
        });
        assertThat(events).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getFormattedMessage())
                    .contains("result: {\"id\":1,\"name\":\"Court A\",\"address\":\"Taipei\"");
        });
        assertThat(indexOf(events, "result:")).isGreaterThan(indexOf(events, "<-- POST"));
    }

    @Test
    void debugLogsPathVariablesAndPageable() throws Exception {
        // given
        long courtId = data.insertCourt("Court A");

        // when
        mockMvc.perform(get("/api/courts/{id}", courtId).header("X-Request-Id", traceId)).andExpect(status().isOk());
        mockMvc.perform(get("/api/courts").param("page", "0").param("size", "5").header("X-Request-Id", traceId))
                .andExpect(status().isOk());

        // then
        List<String> messages = messages(requestLogEvents());
        assertThat(messages).anySatisfy(message -> assertThat(message)
                .contains("args: path={\"id\":\"" + courtId + "\"}"));
        assertThat(messages).anySatisfy(message -> assertThat(message)
                .contains("query={\"page\":[\"0\"],\"size\":[\"5\"]}", "pageable={page=0, size=5, sort=id: ASC}"));
        assertThat(messages).anySatisfy(message -> assertThat(message)
                .contains("result: PageResponse[page=0, size=5, totalElements=1, totalPages=1, contentCount=1]"));
    }

    @Test
    void payloadsAreNotLoggedWhenDebugIsDisabled() throws Exception {
        // given
        loggingSystem.setLogLevel(LOGGING_PACKAGE, LogLevel.INFO);
        try {
            // when
            createCourt();
        } finally {
            loggingSystem.setLogLevel(LOGGING_PACKAGE, null);
        }

        // then
        List<ILoggingEvent> events = requestLogEvents();
        assertThat(events).hasSize(2).allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.INFO));
        assertThat(messages(events)).noneSatisfy(message -> assertThat(message).containsAnyOf("args:", "result:"));
    }

    @Test
    void notFoundLogsFinalStatusAndExceptionClass() throws Exception {
        // when
        mockMvc.perform(get("/api/courts/{id}", IntegrationTestData.MISSING_ID).header("X-Request-Id", traceId))
                .andExpect(status().isNotFound());

        // then
        assertThat(messages(requestLogEvents())).anySatisfy(message -> assertThat(message)
                .contains("<-- GET /api/courts/999999 404 CourtController.get ", "exception=ResourceNotFoundException"));
    }

    @Test
    void conflictLogsFinalStatusAndExceptionClass() throws Exception {
        // given
        long courtId = data.insertCourt("Court A");
        data.insertSession(courtId, 10);

        // when
        mockMvc.perform(delete("/api/courts/{id}", courtId).header("X-Request-Id", traceId))
                .andExpect(status().isConflict());

        // then
        assertThat(messages(requestLogEvents())).anySatisfy(message -> assertThat(message)
                .contains("<-- DELETE /api/courts/" + courtId + " 409 CourtController.delete ",
                        "exception=DataIntegrityViolationException"));
    }

    @Test
    void failureBeforeTheControllerIsStillLogged() throws Exception {
        // when
        mockMvc.perform(post("/api/courts").header("X-Request-Id", traceId)
                        .contentType(MediaType.APPLICATION_JSON).content("{not-json"))
                .andExpect(status().isBadRequest());

        // then
        assertThat(messages(requestLogEvents())).anySatisfy(message -> assertThat(message)
                .contains("<-- POST /api/courts 400 CourtController.create ",
                        "exception=HttpMessageNotReadableException"));
    }

    @Test
    void excludedPathProducesNoRequestLog() throws Exception {
        // when
        mockMvc.perform(get("/api/health").header("X-Request-Id", traceId)).andExpect(status().isOk());

        // then
        assertThat(requestLogEvents()).isEmpty();
    }

    @Test
    void sensitiveFieldsAreMaskedInArgumentsAndResult() throws Exception {
        // when
        mockMvc.perform(post("/test-logging/sign-up").header("X-Request-Id", traceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "alice", "password": "p@ssw0rd",
                                 "credentials": {"token": "tok-main", "label": "main"},
                                 "history": [{"token": "tok-old", "label": "old"}]}
                                """))
                .andExpect(status().isOk());

        // then
        assertThat(messages(requestLogEvents()))
                .filteredOn(message -> message.contains("args:") || message.contains("result:"))
                .hasSize(2)
                .allSatisfy(message -> assertThat(message)
                        .contains("\"username\":\"alice\"", "\"password\":\"***\"", "\"token\":\"***\"")
                        .doesNotContain("p@ssw0rd", "tok-main", "tok-old"));
    }

    private void createCourt() throws Exception {
        mockMvc.perform(post("/api/courts").header("X-Request-Id", traceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Court A", "address": "Taipei"}
                                """))
                .andExpect(status().isCreated());
    }

    private List<ILoggingEvent> requestLogEvents() {
        return logs.withTraceId(traceId).stream()
                .filter(event -> REQUEST_LOGGERS.contains(event.getLoggerName()))
                .toList();
    }

    private static List<String> messages(List<ILoggingEvent> events) {
        return events.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static int indexOf(List<ILoggingEvent> events, String fragment) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).getFormattedMessage().contains(fragment)) {
                return i;
            }
        }
        return -1;
    }
}
