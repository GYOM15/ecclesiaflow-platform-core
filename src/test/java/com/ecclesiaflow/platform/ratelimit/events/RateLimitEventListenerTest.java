package com.ecclesiaflow.platform.ratelimit.events;

import nl.altindag.log.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitEventListenerTest {

    private final RateLimitEventListener listener = new RateLimitEventListener();
    private final LogCaptor logs = LogCaptor.forClass(RateLimitEventListener.class);

    @AfterEach
    void tearDown() {
        logs.close();
    }

    @Test
    @DisplayName("fail-open: WARN that the rule is not enforced, with the root cause sanitized")
    void failOpenIsAWarningWithASanitizedCause() {
        RuntimeException cause = new RedisConnectionFailureException("Unable to connect to Redis",
                new IllegalStateException("Unable to connect to redis/<unresolved>:6379"));

        listener.onCounterUnavailable(new RateLimitEvents.CounterUnavailable("import", true, cause));

        assertThat(logs.getWarnLogs()).singleElement().asString()
                .contains("'import'")
                .contains("not enforced")
                .contains("RedisConnectionFailureException")
                .doesNotContain("redis/")
                .doesNotContain("<unresolved>");
    }

    @Test
    @DisplayName("fail-closed: WARN that the rule is refusing")
    void failClosedIsAWarningThatItRefuses() {
        RuntimeException cause = new RedisConnectionFailureException("Unable to connect to cache.internal:6379");

        listener.onCounterUnavailable(new RateLimitEvents.CounterUnavailable("export", false, cause));

        assertThat(logs.getWarnLogs()).singleElement().asString()
                .contains("'export'")
                .contains("refusing")
                .doesNotContain("cache.internal");
    }

    @Test
    @DisplayName("no exception at all: Redis answered without a count")
    void noCountReturned() {
        listener.onCounterUnavailable(new RateLimitEvents.CounterUnavailable("import", true, null));

        assertThat(logs.getWarnLogs()).singleElement().asString().contains("no count");
    }
}
