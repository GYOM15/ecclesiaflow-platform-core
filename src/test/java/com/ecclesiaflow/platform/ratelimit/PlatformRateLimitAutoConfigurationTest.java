package com.ecclesiaflow.platform.ratelimit;

import com.ecclesiaflow.platform.ratelimit.autoconfigure.PlatformRateLimitAutoConfiguration;
import com.ecclesiaflow.platform.ratelimit.events.RateLimitEventListener;
import nl.altindag.log.LogCaptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Both auto-configurations in the order Spring Boot decides: the limiter must be wired, not merely written. */
class PlatformRateLimitAutoConfigurationTest {

    /** The library ships no Redis client, so the factory is a stand-in that never dials: only the order is tested. */
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
    @DisplayName("a blind counter reaches the listener, which logs it without the Redis host")
    void aBlindCounterIsLoggedByTheListener() {
        RedisConnectionFactory unreachable = mock(RedisConnectionFactory.class);
        when(unreachable.getConnection()).thenThrow(
                new RedisConnectionFailureException("Unable to connect to redis/<unresolved>:6379"));

        try (LogCaptor logs = LogCaptor.forClass(RateLimitEventListener.class)) {
            new ApplicationContextRunner()
                    .withBean(RedisConnectionFactory.class, () -> unreachable)
                    .withConfiguration(AutoConfigurations.of(
                            RedisAutoConfiguration.class, PlatformRateLimitAutoConfiguration.class))
                    .run(context -> {
                        assertThat(context).hasSingleBean(RateLimitEventListener.class);
                        context.getBean(RateLimiter.class)
                                .consume(RateLimitRule.perChurch("import", 3, Duration.ofMinutes(1)), "church-1");
                    });

            assertThat(logs.getWarnLogs()).singleElement().asString()
                    .contains("'import'")
                    .doesNotContain("redis/")
                    .doesNotContain("<unresolved>");
        }
    }

    @Test
    @DisplayName("switching it off leaves no limiter and no interceptor")
    void honoursTheKillSwitch() {
        runner.withPropertyValues("ecclesiaflow.rate-limit.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(RateLimiter.class));
    }
}
