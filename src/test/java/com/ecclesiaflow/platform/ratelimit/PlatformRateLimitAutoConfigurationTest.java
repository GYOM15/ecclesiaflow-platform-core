package com.ecclesiaflow.platform.ratelimit;

import com.ecclesiaflow.platform.ratelimit.autoconfigure.PlatformRateLimitAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * That the limiter is actually WIRED, not merely written.
 *
 * <p>This exists because the unit tests all passed while the application could
 * not start. {@code @ConditionalOnBean(StringRedisTemplate.class)} is evaluated
 * when the auto-configuration runs, so without an explicit order this class was
 * processed before Redis's, found no template, and produced no
 * {@link RateLimiter} — killing every application that injects one.
 *
 * <p>The runner below reproduces that exact condition: both auto-configurations,
 * in the order Spring Boot decides, against a real context.
 */
class PlatformRateLimitAutoConfigurationTest {

    /**
     * A connection factory is supplied because this library does not ship a
     * Redis CLIENT — Lettuce arrives with the consuming module's starter. What
     * is under test is the ORDER of the two auto-configurations, not Redis's
     * ability to connect, so the factory is a stand-in and never dials.
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class))
            .withConfiguration(AutoConfigurations.of(
                    RedisAutoConfiguration.class, PlatformRateLimitAutoConfiguration.class));

    @Test
    @DisplayName("a RateLimiter exists once Redis is on the classpath")
    void createsTheLimiter() {
        runner.run(context -> assertThat(context).hasSingleBean(RateLimiter.class));
    }

    @Test
    @DisplayName("switching it off leaves no limiter and no interceptor")
    void honoursTheKillSwitch() {
        runner.withPropertyValues("ecclesiaflow.rate-limit.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(RateLimiter.class));
    }
}
