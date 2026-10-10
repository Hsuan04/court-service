package com.courtservice.common.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Where Redisson connects, bound from {@code app.redis.*}. Override with the {@code REDIS_URL}
 * environment variable; never commit credentials here.
 *
 * @param address the Redis address, for example {@code redis://localhost:6379}
 */
@Validated
@ConfigurationProperties("app.redis")
public record RedisConnectionProperties(@NotBlank @DefaultValue("redis://localhost:6379") String address) {
}
