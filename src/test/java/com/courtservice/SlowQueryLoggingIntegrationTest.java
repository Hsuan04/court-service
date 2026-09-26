package com.courtservice;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With the production settings (only slow queries, threshold 100ms), a statement above the
 * threshold is logged at WARN with its trace id and a fast statement is not logged at all.
 */
@ExtendWith(OutputCaptureExtension.class)
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "app.logging.sql.log-all-queries=false",
        "app.logging.sql.slow-query-threshold-ms=100",
        "app.logging.sql.log-parameters=false"
})
class SlowQueryLoggingIntegrationTest {

    private static final String TRACE_ID_KEY = "traceId";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clearMdc() {
        MDC.remove(TRACE_ID_KEY);
    }

    @Test
    void onlyQueriesAboveTheThresholdAreLoggedAtWarn(CapturedOutput output) {
        // given
        String traceId = "slow-" + UUID.randomUUID();
        MDC.put(TRACE_ID_KEY, traceId);

        // when
        jdbcTemplate.execute("select pg_sleep(0.2)");
        jdbcTemplate.queryForObject("select 42", Integer.class);

        // then
        List<String> sqlLines = output.getOut().lines()
                .filter(line -> line.contains("SqlExecutionLogger") && line.contains("[traceId=" + traceId + "]"))
                .toList();
        assertThat(sqlLines).singleElement().satisfies(line -> assertThat(line)
                .contains(" WARN ", "(threshold 100ms)", "select pg_sleep(0.2)")
                .containsPattern("Slow SQL \\d{3,}ms"));
    }
}
