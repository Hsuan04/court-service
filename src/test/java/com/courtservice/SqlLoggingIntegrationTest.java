package com.courtservice;

import ch.qos.logback.classic.Level;
import com.courtservice.common.logging.CapturedLogEvents;
import com.courtservice.common.logging.SqlExecutionLogger;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Under the local profile every statement is logged at DEBUG with its execution time and the
 * request's trace id. Assertions inspect logging events rather than console text, so they do
 * not depend on the log pattern.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SqlLoggingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    private CapturedLogEvents sqlLogs;

    @BeforeEach
    void captureSqlLogs() {
        sqlLogs = CapturedLogEvents.attach(SqlExecutionLogger.class.getName());
    }

    @AfterEach
    void detachSqlLogs() {
        sqlLogs.close();
    }

    @Test
    void dataSourceIsProxied() {
        assertThat(dataSource).isInstanceOf(ProxyDataSource.class);
    }

    @Test
    void sqlIsLoggedWithExecutionTimeParametersAndTraceId() throws Exception {
        // given
        String traceId = "sql-" + UUID.randomUUID();
        String courtName = "Court " + traceId;

        // when
        mockMvc.perform(post("/api/courts").header("X-Request-Id", traceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"" + courtName + "\"}"))
                .andExpect(status().isCreated());

        // then
        assertThat(sqlLogs.withTraceId(traceId)).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getFormattedMessage())
                    .contains("insert into court", courtName)
                    .containsPattern("^SQL \\d+ms rows=1 \\|");
        });
    }

    @Test
    void nullParameterIsLoggedAsNull() throws Exception {
        // given
        String traceId = "sql-null-" + UUID.randomUUID();

        // when: address is omitted, so Hibernate binds it with setNull
        mockMvc.perform(post("/api/courts").header("X-Request-Id", traceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Court without address\"}"))
                .andExpect(status().isCreated());

        // then
        assertThat(sqlLogs.withTraceId(traceId))
                .filteredOn(event -> event.getFormattedMessage().contains("insert into court"))
                .singleElement()
                .satisfies(event -> assertThat(event.getFormattedMessage()).contains("params=[null, "));
    }
}
