package com.courtservice.common.logging;

import net.ttddyy.dsproxy.support.ProxyDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;

/**
 * Wraps the application's {@link DataSource} with a datasource-proxy {@link ProxyDataSource}
 * that reports every statement to {@link SqlExecutionLogger}.
 *
 * <p>A plain {@link BeanPostProcessor} is used instead of the third-party
 * {@code datasource-proxy-spring-boot-starter}: the starter is built against an older Spring
 * Boot line and adds its own configuration namespace, whereas this keeps a single switch,
 * {@code app.logging.sql.enabled}. When that switch is {@code false} (for example in the
 * benchmark profile) nothing is registered and the DataSource is not proxied at all.
 *
 * <p>The post-processor is declared {@code static} because post-processors are created before
 * regular beans; its settings are therefore bound directly from the {@link Environment}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "app.logging.sql.enabled", matchIfMissing = true)
public class SqlLoggingConfiguration {

    private static final String PROPERTIES_PREFIX = "app.logging.sql";

    @Bean
    static BeanPostProcessor sqlLoggingDataSourcePostProcessor(Environment environment) {
        SqlLoggingProperties properties = Binder.get(environment)
                .bindOrCreate(PROPERTIES_PREFIX, SqlLoggingProperties.class);
        SqlExecutionLogger sqlExecutionLogger = new SqlExecutionLogger(properties);
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource dataSource && !(bean instanceof ProxyDataSource)) {
                    return ProxyDataSourceBuilder.create(dataSource)
                            .name(beanName)
                            .listener(sqlExecutionLogger)
                            .build();
                }
                return bean;
            }
        };
    }
}
