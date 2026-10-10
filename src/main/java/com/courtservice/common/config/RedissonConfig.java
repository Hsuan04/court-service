package com.courtservice.common.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the {@link RedissonClient} used by the distributed booking lock.
 *
 * <p>The client is built here instead of through {@code redisson-spring-boot-starter} to keep one
 * explicit, version-independent bean. {@link Redisson#create(Config)} connects immediately, so the
 * application needs a reachable Redis at startup in every profile, whichever booking mode is
 * active; this keeps the wiring simple at the cost of that dependency.
 */
@Configuration(proxyBeanMethods = false)
public class RedissonConfig {

    /**
     * Connects to a single Redis server.
     *
     * @param properties where to connect
     * @return the client, shut down with the application context
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(RedisConnectionProperties properties) {
        Config config = new Config();
        config.useSingleServer().setAddress(properties.address());
        return Redisson.create(config);
    }
}
