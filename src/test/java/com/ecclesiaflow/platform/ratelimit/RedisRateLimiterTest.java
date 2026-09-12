package com.ecclesiaflow.platform.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The counter itself: what it counts, when it stops counting, and what it does blind. */
class RedisRateLimiterTest {

    private static final RateLimitRule RULE =
            RateLimitRule.perChurch("import", 3, Duration.ofMinutes(1));

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private RedisRateLimiter limiter;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        limiter = new RedisRateLimiter(redis);
    }

    @Test
    @DisplayName("a call under the limit passes, and says how much is left")
    void allowsUnderTheLimit() {
        when(values.increment(anyString())).thenReturn(2L);

        RateLimitDecision decision = limiter.consume(RULE, "church-1");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.limit()).isEqualTo(3);
        assertThat(decision.remaining()).isEqualTo(1);
    }

    @Test
    @DisplayName("the expiry is set on the FIRST hit only")
    void setsTheExpiryOnlyOnTheFirstHit() {
        // Refreshing it on every call would let a steady stream hold the window
        // open for ever: the counter would never reset and the caller would be
        // locked out permanently after one burst.
        when(values.increment(anyString())).thenReturn(1L);
        limiter.consume(RULE, "church-1");
        verify(redis).expire(anyString(), any(Duration.class));

        when(values.increment(anyString())).thenReturn(2L);
        limiter.consume(RULE, "church-1");
        verify(redis).expire(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("past the limit it refuses, and says when to come back")
    void refusesPastTheLimit() {
        when(values.increment(anyString())).thenReturn(4L);

        RateLimitDecision decision = limiter.consume(RULE, "church-1");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.remaining()).isZero();
        // Never zero: a refusal with no delay teaches a client to retry at once,
        // which turns one limiter into a tight loop.
        assertThat(decision.retryAfterSeconds()).isBetween(1L, 60L);
    }

    @Test
    @DisplayName("two subjects are counted apart — one church cannot spend another's")
    void countsSubjectsApart() {
        when(values.increment("ecclesiaflow:ratelimit:import:church-1:" + window()))
                .thenReturn(4L);
        when(values.increment("ecclesiaflow:ratelimit:import:church-2:" + window()))
                .thenReturn(1L);

        assertThat(limiter.consume(RULE, "church-1").allowed()).isFalse();
        assertThat(limiter.consume(RULE, "church-2").allowed()).isTrue();
    }

    @Test
    @DisplayName("a rule NAME separates counters too — two operations do not share one")
    void countsRulesApart() {
        RateLimitRule other = RateLimitRule.perChurch("export", 3, Duration.ofMinutes(1));
        when(values.increment("ecclesiaflow:ratelimit:import:church-1:" + window())).thenReturn(4L);
        when(values.increment("ecclesiaflow:ratelimit:export:church-1:" + window())).thenReturn(1L);

        assertThat(limiter.consume(RULE, "church-1").allowed()).isFalse();
        assertThat(limiter.consume(other, "church-1").allowed()).isTrue();
    }

    @Test
    @DisplayName("Redis unreachable: a fail-OPEN rule lets the call through")
    void failsOpenWhenRedisIsDown() {
        // These operations already passed authentication and a capability check.
        // The limiter guards against abuse by someone otherwise entitled, so
        // refusing a treasurer's export because a cache is down trades a real
        // outage for a hypothetical abuse.
        when(values.increment(anyString()))
                .thenThrow(new RedisConnectionFailureException("down"));

        RateLimitDecision decision = limiter.consume(RULE, "church-1");

        assertThat(decision.allowed()).isTrue();
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("Redis unreachable: a fail-CLOSED rule refuses")
    void failsClosedWhenTheRuleSaysSo() {
        // Reserved for operations whose abuse costs money or cannot be undone.
        when(values.increment(anyString()))
                .thenThrow(new RedisConnectionFailureException("down"));

        RateLimitDecision decision = limiter.consume(RULE.failClosed(), "church-1");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isEqualTo(60);
    }

    @Test
    @DisplayName("a null count is treated as unreadable, not as zero")
    void treatsANullCountAsUnreadable() {
        // Reading it as 0 would admit every call for ever while looking healthy.
        when(values.increment(anyString())).thenReturn(null);

        assertThat(limiter.consume(RULE, "church-1").allowed()).isTrue();
        assertThat(limiter.consume(RULE.failClosed(), "church-1").allowed()).isFalse();
    }

    private static long window() {
        return java.time.Instant.now().getEpochSecond() / 60;
    }
}
