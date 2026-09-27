package com.courtservice.common.logging;

import com.courtservice.common.logging.SqlExecutionLogger.Decision;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.proxy.ParameterSetOperation;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlExecutionLoggerTest {

    private static final long THRESHOLD_MS = 100;

    @Test
    void slowQueryIsAlwaysLoggedAtWarn() {
        SqlExecutionLogger logger = new SqlExecutionLogger(new SqlLoggingProperties(true, false, THRESHOLD_MS, false));

        assertThat(logger.decide(THRESHOLD_MS, false)).isEqualTo(Decision.WARN);
        assertThat(logger.decide(THRESHOLD_MS + 50, true)).isEqualTo(Decision.WARN);
    }

    @Test
    void fastQueryIsSkippedWhenNotLoggingAllQueries() {
        SqlExecutionLogger logger = new SqlExecutionLogger(new SqlLoggingProperties(true, false, THRESHOLD_MS, false));

        assertThat(logger.decide(THRESHOLD_MS - 1, true)).isEqualTo(Decision.NONE);
    }

    @Test
    void fastQueryIsLoggedAtDebugWhenLoggingAllQueriesAndDebugEnabled() {
        SqlExecutionLogger logger = new SqlExecutionLogger(new SqlLoggingProperties(true, true, THRESHOLD_MS, false));

        assertThat(logger.decide(1, true)).isEqualTo(Decision.DEBUG);
        assertThat(logger.decide(1, false)).isEqualTo(Decision.NONE);
    }

    @Test
    void describeOmitsParametersByDefault() throws Exception {
        SqlExecutionLogger logger = new SqlExecutionLogger(new SqlLoggingProperties(true, true, THRESHOLD_MS, false));

        assertThat(logger.describe(List.of(query("select *\n  from member where name = ?", "Alice"))))
                .isEqualTo("select * from member where name = ?");
    }

    @Test
    void describeIncludesParametersInIndexOrderWhenEnabled() throws Exception {
        SqlExecutionLogger logger = new SqlExecutionLogger(new SqlLoggingProperties(true, true, THRESHOLD_MS, true));
        List<Object> values = new ArrayList<>();
        for (int i = 1; i <= 11; i++) {
            values.add("v" + i);
        }

        String description = logger.describe(List.of(query("insert into t values (?)", values.toArray())));

        assertThat(description).isEqualTo("insert into t values (?) | params=[v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11]");
    }

    @Test
    void setNullIsLoggedAsNullInsteadOfTheSqlTypeCode() throws Exception {
        // given
        SqlExecutionLogger logger = new SqlExecutionLogger(new SqlLoggingProperties(true, true, THRESHOLD_MS, true));
        QueryInfo queryInfo = new QueryInfo("insert into court (address, name, note) values (?, ?, ?)");
        queryInfo.getParametersList().add(List.of(
                operation("setNull", new Class<?>[]{int.class, int.class, String.class}, 3, Types.VARCHAR, "VARCHAR"),
                operation("setString", new Class<?>[]{int.class, String.class}, 2, "Court A"),
                operation("setNull", new Class<?>[]{int.class, int.class}, 1, Types.VARCHAR)));

        // when
        String description = logger.describe(List.of(queryInfo));

        // then
        assertThat(description)
                .isEqualTo("insert into court (address, name, note) values (?, ?, ?) | params=[null, Court A, null]")
                .doesNotContain(String.valueOf(Types.VARCHAR));
    }

    @Test
    void registerOutParameterIsLoggedAsOutInsteadOfTheSqlTypeCode() throws Exception {
        // given
        SqlExecutionLogger logger = new SqlExecutionLogger(new SqlLoggingProperties(true, true, THRESHOLD_MS, true));
        QueryInfo queryInfo = new QueryInfo("{call next_id(?, ?)}");
        queryInfo.getParametersList().add(List.of(
                new ParameterSetOperation(CallableStatement.class.getMethod("registerOutParameter", int.class, int.class),
                        new Object[]{2, Types.BIGINT}),
                operation("setString", new Class<?>[]{int.class, String.class}, 1, "court")));

        // when
        String description = logger.describe(List.of(queryInfo));

        // then
        assertThat(description).isEqualTo("{call next_id(?, ?)} | params=[court, OUT]");
    }

    @Test
    void rowsReportsUpdateAndBatchCountsOnly() {
        assertThat(SqlExecutionLogger.rows(3)).isEqualTo(" rows=3");
        assertThat(SqlExecutionLogger.rows(new int[]{1, 2})).isEqualTo(" rows=3");
        assertThat(SqlExecutionLogger.rows(Boolean.TRUE)).isEmpty();
        assertThat(SqlExecutionLogger.rows(null)).isEmpty();
    }

    private static ParameterSetOperation operation(String methodName, Class<?>[] parameterTypes, Object... args)
            throws Exception {
        return new ParameterSetOperation(PreparedStatement.class.getMethod(methodName, parameterTypes), args);
    }

    /** Builds a query whose parameters are registered in reverse index order, as a driver may do. */
    private static QueryInfo query(String sql, Object... values) throws Exception {
        QueryInfo queryInfo = new QueryInfo(sql);
        Method setString = PreparedStatement.class.getMethod("setString", int.class, String.class);
        List<ParameterSetOperation> operations = new ArrayList<>();
        for (int i = values.length; i >= 1; i--) {
            operations.add(new ParameterSetOperation(setString, new Object[]{i, values[i - 1]}));
        }
        queryInfo.getParametersList().add(operations);
        return queryInfo;
    }
}
