package com.courtservice;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Starts throwaway PostgreSQL and Redis containers for integration tests. PostgreSQL is wired in
 * through {@code @ServiceConnection}; Redis through {@code app.redis.address}, which the Redisson
 * client reads. Neither container has a volume, so their data is gone when the test JVM exits.
 * Public because the integration tests live in the feature packages they cover.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    private static final int REDIS_PORT = 6379;

    @Bean
    @ServiceConnection
    public PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:17"));
    }

    @Bean
    public GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(REDIS_PORT);
    }

    @Bean
    public DynamicPropertyRegistrar redisProperties(GenericContainer<?> redisContainer) {
        return registry -> registry.add("app.redis.address",
                () -> "redis://" + redisContainer.getHost() + ":" + redisContainer.getMappedPort(REDIS_PORT));
    }

}
