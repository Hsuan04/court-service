package com.courtservice.common.logging;

import lombok.extern.slf4j.Slf4j;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import net.ttddyy.dsproxy.proxy.ParameterSetOperation;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Logs executed SQL statements with their execution time.
 *
 * <p>A statement at or above {@code slowQueryThresholdMs} is always logged at WARN. Faster
 * statements are logged at DEBUG only when {@code logAllQueries} is enabled. Listeners run
 * synchronously on the calling thread, so each line carries the request's {@code traceId}
 * from the MDC.
 *
 * <p>The affected row count is reported for updates and batches only. Counting SELECT rows
 * would require proxying every {@code ResultSet}, which is not worth the overhead here.
 */
@Slf4j
public class SqlExecutionLogger implements QueryExecutionListener {

    /** How a single statement execution should be logged. */
    enum Decision { NONE, DEBUG, WARN }

    // JDBC parameters are set by numeric index; named parameters (callable statements) sort by name.
    private static final Comparator<ParameterSetOperation> PARAMETER_ORDER = Comparator
            .comparing((ParameterSetOperation operation) -> !(operation.getArgs()[0] instanceof Number))
            .thenComparingInt(operation -> operation.getArgs()[0] instanceof Number index ? index.intValue() : 0)
            .thenComparing(operation -> String.valueOf(operation.getArgs()[0]));

    private final SqlLoggingProperties properties;

    /**
     * @param properties the SQL logging settings
     */
    public SqlExecutionLogger(SqlLoggingProperties properties) {
        this.properties = properties;
    }

    @Override
    public void beforeQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
        // Nothing to do before execution; timing is provided by datasource-proxy.
    }

    @Override
    public void afterQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
        long elapsedMs = execInfo.getElapsedTime();
        Decision decision = decide(elapsedMs, log.isDebugEnabled());
        if (decision == Decision.WARN) {
            log.warn("Slow SQL {}ms (threshold {}ms){} | {}", elapsedMs, properties.slowQueryThresholdMs(),
                    rows(execInfo.getResult()), describe(queryInfoList));
        } else if (decision == Decision.DEBUG) {
            log.debug("SQL {}ms{} | {}", elapsedMs, rows(execInfo.getResult()), describe(queryInfoList));
        }
    }

    Decision decide(long elapsedMs, boolean debugEnabled) {
        if (elapsedMs >= properties.slowQueryThresholdMs()) {
            return Decision.WARN;
        }
        return properties.logAllQueries() && debugEnabled ? Decision.DEBUG : Decision.NONE;
    }

    String describe(List<QueryInfo> queryInfoList) {
        return queryInfoList.stream()
                .map(this::describe)
                .collect(Collectors.joining(" ; "));
    }

    private String describe(QueryInfo queryInfo) {
        String sql = queryInfo.getQuery().replaceAll("\\s+", " ").trim();
        List<List<ParameterSetOperation>> parameterSets = queryInfo.getParametersList().stream()
                .filter(operations -> !operations.isEmpty())
                .toList();
        if (!properties.logParameters() || parameterSets.isEmpty()) {
            return sql;
        }
        String parameters = parameterSets.stream()
                .map(SqlExecutionLogger::describeParameters)
                .collect(Collectors.joining(", "));
        return sql + " | params=" + parameters;
    }

    private static String describeParameters(List<ParameterSetOperation> operations) {
        return operations.stream()
                .sorted(PARAMETER_ORDER)
                .map(operation -> String.valueOf(operation.getArgs()[1]))
                .collect(Collectors.joining(", ", "[", "]"));
    }

    static String rows(Object result) {
        if (result instanceof Number count) {
            return " rows=" + count;
        }
        if (result instanceof int[] counts) {
            return " rows=" + Arrays.stream(counts).sum();
        }
        if (result instanceof long[] counts) {
            return " rows=" + Arrays.stream(counts).sum();
        }
        return "";
    }
}
