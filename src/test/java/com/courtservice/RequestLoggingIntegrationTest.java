package com.courtservice;

import loggingsupport.TestLoggingControllerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies request logging end to end under the local profile, where the logging package is at
 * DEBUG. Each test sends its own X-Request-Id and only inspects the lines carrying that trace id,
 * so tests stay independent of each other and of other output.
 */
@ExtendWith(OutputCaptureExtension.class)
@Import({TestcontainersConfiguration.class, TestLoggingControllerConfig.class})
@SpringBootTest
@AutoConfigureMockMvc
class RequestLoggingIntegrationTest {

    private static final String LOGGING_PACKAGE = "com.courtservice.common.logging";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LoggingSystem loggingSystem;

    private IntegrationTestData data;

    private String traceId;

    @BeforeEach
    void setUp() {
        data = new IntegrationTestData(jdbcTemplate);
        data.truncateAll();
        traceId = "log-test-" + UUID.randomUUID();
    }

    @Test
    void successfulCallLogsEntryAndExitAtInfoWithStatusDurationAndTraceId(CapturedOutput output) throws Exception {
        // when
        createCourt();

        // then
        List<String> lines = requestLogLines(output);
        assertThat(lines).filteredOn(line -> line.contains(" INFO ")).hasSize(2);
        assertThat(lines).anySatisfy(line -> assertThat(line)
                .contains(" INFO ", "[traceId=" + traceId + "]", "--> POST /api/courts CourtController.create"));
        assertThat(lines).anySatisfy(line -> assertThat(line)
                .contains(" INFO ", "[traceId=" + traceId + "]", "<-- POST /api/courts 201 CourtController.create ")
                .containsPattern(" \\d+ms$"));
    }

    @Test
    void debugLogsArgumentsAndResult(CapturedOutput output) throws Exception {
        // when
        createCourt();

        // then
        List<String> lines = requestLogLines(output);
        assertThat(lines).anySatisfy(line -> assertThat(line)
                .contains(" DEBUG ", "args: path={}, query={}, body={\"name\":\"Court A\",\"address\":\"Taipei\"}"));
        assertThat(lines).anySatisfy(line -> assertThat(line)
                .contains(" DEBUG ", "result: {\"id\":1,\"name\":\"Court A\",\"address\":\"Taipei\""));
        assertThat(indexOf(lines, "result:")).isGreaterThan(indexOf(lines, "<-- POST"));
    }

    @Test
    void debugLogsPathVariablesAndPageable(CapturedOutput output) throws Exception {
        // given
        long courtId = data.insertCourt("Court A");

        // when
        mockMvc.perform(get("/api/courts/{id}", courtId).header("X-Request-Id", traceId)).andExpect(status().isOk());
        mockMvc.perform(get("/api/courts").param("page", "0").param("size", "5").header("X-Request-Id", traceId))
                .andExpect(status().isOk());

        // then
        List<String> lines = requestLogLines(output);
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("args: path={\"id\":\"" + courtId + "\"}"));
        assertThat(lines).anySatisfy(line -> assertThat(line)
                .contains("query={\"page\":[\"0\"],\"size\":[\"5\"]}", "pageable={page=0, size=5, sort=id: ASC}"));
        assertThat(lines).anySatisfy(line -> assertThat(line)
                .contains("result: PageResponse[page=0, size=5, totalElements=1, totalPages=1, contentCount=1]"));
    }

    @Test
    void payloadsAreNotLoggedWhenDebugIsDisabled(CapturedOutput output) throws Exception {
        // given
        loggingSystem.setLogLevel(LOGGING_PACKAGE, LogLevel.INFO);
        try {
            // when
            createCourt();
        } finally {
            loggingSystem.setLogLevel(LOGGING_PACKAGE, null);
        }

        // then
        List<String> lines = requestLogLines(output);
        assertThat(lines).hasSize(2).allSatisfy(line -> assertThat(line).contains(" INFO "));
        assertThat(lines).noneSatisfy(line -> assertThat(line).containsAnyOf("args:", "result:"));
    }

    @Test
    void notFoundLogsFinalStatusAndExceptionClass(CapturedOutput output) throws Exception {
        // when
        mockMvc.perform(get("/api/courts/{id}", IntegrationTestData.MISSING_ID).header("X-Request-Id", traceId))
                .andExpect(status().isNotFound());

        // then
        assertThat(requestLogLines(output)).anySatisfy(line -> assertThat(line)
                .contains("<-- GET /api/courts/999999 404 CourtController.get ", "exception=ResourceNotFoundException"));
    }

    @Test
    void conflictLogsFinalStatusAndExceptionClass(CapturedOutput output) throws Exception {
        // given
        long courtId = data.insertCourt("Court A");
        data.insertSession(courtId, 10);

        // when
        mockMvc.perform(delete("/api/courts/{id}", courtId).header("X-Request-Id", traceId))
                .andExpect(status().isConflict());

        // then
        assertThat(requestLogLines(output)).anySatisfy(line -> assertThat(line)
                .contains("<-- DELETE /api/courts/" + courtId + " 409 CourtController.delete ",
                        "exception=DataIntegrityViolationException"));
    }

    @Test
    void failureBeforeTheControllerIsStillLogged(CapturedOutput output) throws Exception {
        // when
        mockMvc.perform(post("/api/courts").header("X-Request-Id", traceId)
                        .contentType(MediaType.APPLICATION_JSON).content("{not-json"))
                .andExpect(status().isBadRequest());

        // then
        assertThat(requestLogLines(output)).anySatisfy(line -> assertThat(line)
                .contains("<-- POST /api/courts 400 CourtController.create ",
                        "exception=HttpMessageNotReadableException"));
    }

    @Test
    void excludedPathProducesNoRequestLog(CapturedOutput output) throws Exception {
        // when
        mockMvc.perform(get("/api/health").header("X-Request-Id", traceId)).andExpect(status().isOk());

        // then
        assertThat(requestLogLines(output)).isEmpty();
    }

    @Test
    void sensitiveFieldsAreMaskedInArgumentsAndResult(CapturedOutput output) throws Exception {
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
        List<String> lines = requestLogLines(output);
        assertThat(lines).filteredOn(line -> line.contains("args:") || line.contains("result:")).hasSize(2)
                .allSatisfy(line -> assertThat(line)
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

    private List<String> requestLogLines(CapturedOutput output) {
        return output.getOut().lines()
                .filter(line -> line.contains("[traceId=" + traceId + "]"))
                .filter(line -> line.contains("RequestLogging"))
                .toList();
    }

    private static int indexOf(List<String> lines, String fragment) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(fragment)) {
                return i;
            }
        }
        return -1;
    }
}
