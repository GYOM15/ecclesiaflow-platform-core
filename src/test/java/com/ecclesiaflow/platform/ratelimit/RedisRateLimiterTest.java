package com.ecclesiaflow.platform.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
        when(values.increment(anyString(), anyLong())).thenReturn(2L);

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
        when(values.increment(anyString(), anyLong())).thenReturn(1L);
        limiter.consume(RULE, "church-1");
        verify(redis).expire(anyString(), any(Duration.class));

        when(values.increment(anyString(), anyLong())).thenReturn(2L);
        limiter.consume(RULE, "church-1");
        verify(redis).expire(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("past the limit it refuses, and says when to come back")
    void refusesPastTheLimit() {
        when(values.increment(anyString(), anyLong())).thenReturn(4L);

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
        when(values.increment("ecclesiaflow:ratelimit:import:church-1:" + window(), 1L))
                .thenReturn(4L);
        when(values.increment("ecclesiaflow:ratelimit:import:church-2:" + window(), 1L))
                .thenReturn(1L);

        assertThat(limiter.consume(RULE, "church-1").allowed()).isFalse();
        assertThat(limiter.consume(RULE, "church-2").allowed()).isTrue();
    }

    @Test
    @DisplayName("a rule NAME separates counters too — two operations do not share one")
    void countsRulesApart() {
        RateLimitRule other = RateLimitRule.perChurch("export", 3, Duration.ofMinutes(1));
        when(values.increment("ecclesiaflow:ratelimit:import:church-1:" + window(), 1L)).thenReturn(4L);
        when(values.increment("ecclesiaflow:ratelimit:export:church-1:" + window(), 1L)).thenReturn(1L);

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
        when(values.increment(anyString(), anyLong()))
                .thenThrow(new RedisConnectionFailureException("down"));

        RateLimitDecision decision = limiter.consume(RULE, "church-1");

        assertThat(decision.allowed()).isTrue();
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("Redis unreachable: a fail-CLOSED rule refuses")
    void failsClosedWhenTheRuleSaysSo() {
        // Reserved for operations whose abuse costs money or cannot be undone.
        when(values.increment(anyString(), anyLong()))
                .thenThrow(new RedisConnectionFailureException("down"));

        RateLimitDecision decision = limiter.consume(RULE.failClosed(), "church-1");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isEqualTo(60);
    }

    @Test
    @DisplayName("a null count is treated as unreadable, not as zero")
    void treatsANullCountAsUnreadable() {
        // Reading it as 0 would admit every call for ever while looking healthy.
        when(values.increment(anyString(), anyLong())).thenReturn(null);

        assertThat(limiter.consume(RULE, "church-1").allowed()).isTrue();
        assertThat(limiter.consume(RULE.failClosed(), "church-1").allowed()).isFalse();
    }

    private static long window() {
        return java.time.Instant.now().getEpochSecond() / 60;
    }

    @Test
    @DisplayName("F059: a batch counts its real cost, all or nothing")
    void aBatchCountsItsRealCost() {
        // A bulk import is ONE request that mints one invitation and sends one
        // email per row, so counting it as one call let a thousand-row file walk
        // past a two-hundred invitation ceiling: the ceiling counted the wrong
        // thing.
        // RULE allows 3; a batch of 3 lands exactly on the limit and passes.
        when(values.increment(anyString(), anyLong())).thenReturn(3L);

        RateLimitDecision decision = limiter.consume(RULE, "church-1", 3);

        verify(values).increment(anyString(), eq(3L));
        assertThat(decision.allowed()).isTrue();
    }

    @Test
    @DisplayName("F059: a batch that would cross the limit is refused whole")
    void aBatchThatCrossesTheLimitIsRefused() {
        // Counting part of a batch and refusing the rest would leave the caller
        // having half-sent something.
        when(values.increment(anyString(), anyLong())).thenReturn((long) RULE.limit() + 1);

        assertThat(limiter.consume(RULE, "church-1", 5).allowed()).isFalse();
    }

    @Test
    @DisplayName("A cost below one is a programming error, not a free call")
    void aCostBelowOneIsRefusedOutright() {
        assertThatThrownBy(() -> limiter.consume(RULE, "church-1", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
