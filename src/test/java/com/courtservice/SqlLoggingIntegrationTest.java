package com.courtservice;

import net.ttddyy.dsproxy.support.ProxyDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
 * request's trace id.
 */
@ExtendWith(OutputCaptureExtension.class)
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SqlLoggingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    @Test
    void dataSourceIsProxied() {
        assertThat(dataSource).isInstanceOf(ProxyDataSource.class);
    }

    @Test
    void sqlIsLoggedWithExecutionTimeParametersAndTraceId(CapturedOutput output) throws Exception {
        // given
        String traceId = "sql-" + UUID.randomUUID();
        String courtName = "Court " + traceId;

        // when
        mockMvc.perform(post("/api/courts").header("X-Request-Id", traceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"" + courtName + "\"}"))
                .andExpect(status().isCreated());

        // then
        assertThat(output.getOut().lines()
                .filter(line -> line.contains("SqlExecutionLogger") && line.contains("[traceId=" + traceId + "]")))
                .anySatisfy(line -> assertThat(line)
                        .contains(" DEBUG ", "insert into court", courtName)
                        .containsPattern("SQL \\d+ms rows=1 \\|"));
    }
}
