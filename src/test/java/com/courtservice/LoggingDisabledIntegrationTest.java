package com.courtservice;

import com.courtservice.common.logging.CapturedLogEvents;
import com.courtservice.common.logging.PayloadFormatter;
import com.courtservice.common.logging.RequestLoggingAspect;
import com.courtservice.common.logging.RequestLoggingInterceptor;
import com.courtservice.common.logging.RequestLoggingPathMatcher;
import com.courtservice.common.logging.SqlLoggingConfiguration;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"app.logging.request.enabled=false", "app.logging.sql.enabled=false"})
@AutoConfigureMockMvc
class LoggingDisabledIntegrationTest {

    private static final String LOGGING_PACKAGE = "com.courtservice.common.logging";

    @Autowired
    private ApplicationContext context;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MockMvc mockMvc;

    private CapturedLogEvents loggingPackageLogs;

    @BeforeEach
    void captureLoggingPackage() {
        loggingPackageLogs = CapturedLogEvents.attach(LOGGING_PACKAGE);
    }

    @AfterEach
    void detachLoggingPackage() {
        loggingPackageLogs.close();
    }

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
    void apiCallProducesNoRequestOrSqlLog() throws Exception {
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
        assertThat(loggingPackageLogs.withTraceId(traceId)).isEmpty();
    }
}
