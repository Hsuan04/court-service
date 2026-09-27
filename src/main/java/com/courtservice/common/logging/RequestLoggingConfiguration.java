package com.courtservice.common.logging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Registers the API request logging components. When {@code app.logging.request.enabled} is
 * {@code false} (for example in the benchmark profile) none of them is registered, so request
 * handling carries no logging overhead at all.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "app.logging.request.enabled", matchIfMissing = true)
@EnableConfigurationProperties(RequestLoggingProperties.class)
public class RequestLoggingConfiguration {

    @Bean
    RequestLoggingPathMatcher requestLoggingPathMatcher(RequestLoggingProperties properties) {
        return new RequestLoggingPathMatcher(properties.excludedPaths());
    }

    @Bean
    PayloadFormatter payloadFormatter(JsonMapper jsonMapper, RequestLoggingProperties properties) {
        return new PayloadFormatter(jsonMapper, properties.maskedFields(), properties.maxPayloadLength());
    }

    @Bean
    RequestLoggingInterceptor requestLoggingInterceptor(RequestLoggingPathMatcher pathMatcher) {
        return new RequestLoggingInterceptor(pathMatcher);
    }

    @Bean
    RequestLoggingAspect requestLoggingAspect(PayloadFormatter payloadFormatter,
                                              RequestLoggingPathMatcher pathMatcher) {
        return new RequestLoggingAspect(payloadFormatter, pathMatcher);
    }

    @Bean
    WebMvcConfigurer requestLoggingWebMvcConfigurer(RequestLoggingInterceptor interceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor);
            }
        };
    }
}
