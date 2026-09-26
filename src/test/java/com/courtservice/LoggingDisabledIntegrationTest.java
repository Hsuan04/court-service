package com.courtservice;

import com.courtservice.common.logging.PayloadFormatter;
import com.courtservice.common.logging.RequestLoggingAspect;
import com.courtservice.common.logging.RequestLoggingInterceptor;
import com.courtservice.common.logging.RequestLoggingPathMatcher;
import com.courtservice.common.logging.SqlLoggingConfiguration;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Simulates the benchmark profile's switches: with request and SQL logging disabled, none of
 * their components is registered and nothing is logged.
 */
@ExtendWith(OutputCaptureExtension.class)
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"app.logging.request.enabled=false", "app.logging.sql.enabled=false"})
@AutoConfigureMockMvc
class LoggingDisabledIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void loggingComponentsAreNotRegistered() {
        assertThat(context.getBeansOfType(RequestLoggingAspect.class)).isEmpty();
        assertThat(context.getBeansOfType(RequestLoggingInterceptor.class)).isEmpty();
        assertThat(context.getBeansOfType(PayloadFormatter.class)).isEmpty();
        assertThat(context.getBeansOfType(RequestLoggingPathMatcher.class)).isEmpty();
        assertThat(context.getBeansOfType(SqlLoggingConfiguration.class)).isEmpty();
        assertThat(dataSource).isNotInstanceOf(ProxyDataSource.class);
    }

    @Test
    void apiCallProducesNoRequestOrSqlLog(CapturedOutput output) throws Exception {
        // given
        String traceId = "disabled-" + UUID.randomUUID();

        // when
        mockMvc.perform(post("/api/courts").header("X-Request-Id", traceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Court A"}
                                """))
                .andExpect(status().isCreated());

        // then
        assertThat(output.getOut().lines().filter(line -> line.contains(traceId)))
                .noneSatisfy(line -> assertThat(line).containsAnyOf("RequestLogging", "SqlExecutionLogger"));
    }
}
