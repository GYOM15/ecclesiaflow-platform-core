package com.ecclesiaflow.platform.ratelimit;

import com.ecclesiaflow.platform.ratelimit.events.RateLimitEvents;
import nl.altindag.log.LogCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class RedisRateLimiterTest {

    private static final RateLimitRule RULE =
            RateLimitRule.perChurch("import", 3, Duration.ofMinutes(1));

    private StringRedisTemplate redis;
    private ApplicationEventPublisher events;
    private RedisRateLimiter limiter;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        events = mock(ApplicationEventPublisher.class);
        limiter = new RedisRateLimiter(redis, events);
    }

    @Nested
    @DisplayName("the admission script")
    class AdmissionScript {

        @Test
        @DisplayName("every call is one run of the admission script, answering a count")
        void runsTheAdmissionScript() {
            whenScriptAnswers(1L);

            limiter.consume(RULE, "church-1");

            ArgumentCaptor<RedisScript<Long>> script = ArgumentCaptor.captor();
            verify(redis).execute(script.capture(), anyList(), any(Object[].class));
            assertThat(script.getValue()).isSameAs(RedisRateLimiter.CONSUME_SCRIPT);
            assertThat(script.getValue().getResultType()).isEqualTo(Long.class);
            assertThat(script.getValue().getSha1()).isNotBlank();
        }

        @Test
        @DisplayName("the script checks the room before it writes, and only gives an expiry to a key with none")
        void theScriptChecksBeforeItWrites() {
            String source = RedisRateLimiter.CONSUME_SCRIPT.getScriptAsString();

            assertThat(source.indexOf("> tonumber(ARGV[2])"))
                    .as("the limit is compared before the counter is touched")
                    .isPositive()
                    .isLessThan(source.indexOf("INCRBY"));
            assertThat(source).contains("return -1");
            assertThat(source.indexOf("'TTL'")).isGreaterThan(source.indexOf("INCRBY"));
            assertThat(source).contains("'EXPIRE', KEYS[1], ARGV[3]");
        }

        @Test
        @DisplayName("the key, then the cost, the limit and the window in seconds, all as strings")
        void passesTheKeyAndTheArguments() {
            whenScriptAnswers(3L);

            limiter.consume(RULE, "church-1", 3);

            verify(redis).execute(eq(RedisRateLimiter.CONSUME_SCRIPT),
                    eq(List.of("ecclesiaflow:ratelimit:import:church-1:" + window())),
                    eq("3"), eq("3"), eq("60"));
        }

        @Test
        @DisplayName("a refusal issues no write of its own: no refund, no expiry, nothing after the script")
        void aRefusalWritesNothing() {
            whenScriptAnswers(RedisRateLimiter.REFUSED);

            assertThat(limiter.consume(RULE, "church-1", 5).allowed()).isFalse();

            verify(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
            verifyNoMoreInteractions(redis);
            verifyNoInteractions(events);
        }
    }

    @Test
    @DisplayName("a call under the limit passes, and says how much is left")
    void allowsUnderTheLimit() {
        whenScriptAnswers(2L);

        RateLimitDecision decision = limiter.consume(RULE, "church-1");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.limit()).isEqualTo(3);
        assertThat(decision.remaining()).isEqualTo(1);
    }

    @Test
    @DisplayName("past the limit it refuses, and says when to come back")
    void refusesPastTheLimit() {
        whenScriptAnswers(RedisRateLimiter.REFUSED);

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
        Map<String, AtomicLong> counters = backScriptWithCounters();
        counters.put("ecclesiaflow:ratelimit:import:church-1:" + window(), new AtomicLong(3));

        assertThat(limiter.consume(RULE, "church-1").allowed()).isFalse();
        assertThat(limiter.consume(RULE, "church-2").allowed()).isTrue();
    }

    @Test
    @DisplayName("a rule NAME separates counters too — two operations do not share one")
    void countsRulesApart() {
        RateLimitRule other = RateLimitRule.perChurch("export", 3, Duration.ofMinutes(1));
        Map<String, AtomicLong> counters = backScriptWithCounters();
        counters.put("ecclesiaflow:ratelimit:import:church-1:" + window(), new AtomicLong(3));

        assertThat(limiter.consume(RULE, "church-1").allowed()).isFalse();
        assertThat(limiter.consume(other, "church-1").allowed()).isTrue();
    }

    @Test
    @DisplayName("Redis unreachable: a fail-OPEN rule lets the call through")
    void failsOpenWhenRedisIsDown() {
        // Already authenticated and capability-checked: a cache outage must not refuse them.
        whenScriptFails(new RedisConnectionFailureException("down"));

        RateLimitDecision decision = limiter.consume(RULE, "church-1");

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    @DisplayName("Redis unreachable: a fail-CLOSED rule refuses")
    void failsClosedWhenTheRuleSaysSo() {
        // Reserved for operations whose abuse costs money or cannot be undone.
        whenScriptFails(new RedisConnectionFailureException("down"));

        RateLimitDecision decision = limiter.consume(RULE.failClosed(), "church-1");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isEqualTo(60);
    }

    @Test
    @DisplayName("a blind counter is not logged by the limiter itself, and its host never reaches a log")
    void aBlindCounterIsNotLoggedInline() {
        try (LogCaptor own = LogCaptor.forClass(RedisRateLimiter.class); LogCaptor all = LogCaptor.forRoot()) {
            whenScriptFails(new RedisConnectionFailureException("Unable to connect to redis.internal:6379"));

            limiter.consume(RULE, "church-1");

            assertThat(own.getLogs()).isEmpty();
            assertThat(all.getLogs()).noneMatch(line -> line.contains("redis.internal"));
        }
    }

    @Test
    @DisplayName("a blind counter is reported as an event carrying the rule, its policy and the cause")
    void aBlindCounterIsPublished() {
        RedisConnectionFailureException down = new RedisConnectionFailureException("down");
        whenScriptFails(down);

        limiter.consume(RULE.failClosed(), "church-1");

        RateLimitEvents.CounterUnavailable event = publishedEvent();
        assertThat(event.ruleName()).isEqualTo("import");
        assertThat(event.failOpen()).isFalse();
        assertThat(event.cause()).isSameAs(down);
    }

    @Test
    @DisplayName("a null count is reported as an event with no cause")
    void aNullCountIsPublishedWithoutCause() {
        whenScriptAnswers(null);

        limiter.consume(RULE, "church-1");

        RateLimitEvents.CounterUnavailable event = publishedEvent();
        assertThat(event.failOpen()).isTrue();
        assertThat(event.cause()).isNull();
    }

    @Test
    @DisplayName("a null count is treated as unreadable, not as zero")
    void treatsANullCountAsUnreadable() {
        // Reading it as 0 would admit every call for ever while looking healthy.
        whenScriptAnswers(null);

        assertThat(limiter.consume(RULE, "church-1").allowed()).isTrue();
        assertThat(limiter.consume(RULE.failClosed(), "church-1").allowed()).isFalse();
    }

    @Test
    @DisplayName("F059: a batch counts its real cost, all or nothing")
    void aBatchCountsItsRealCost() {
        // A bulk import is one request but one invitation per row, so the batch is the cost.
        // RULE allows 3; a batch of 3 lands exactly on the limit and passes.
        Map<String, AtomicLong> counters = backScriptWithCounters();

        RateLimitDecision decision = limiter.consume(RULE, "church-1", 3);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remaining()).isZero();
        assertThat(total(counters)).isEqualTo(3L);
    }

    @Test
    @DisplayName("F059: a batch that would cross the limit is refused whole")
    void aBatchThatCrossesTheLimitIsRefused() {
        // Counting part of a batch and refusing the rest would leave the caller
        // having half-sent something.
        Map<String, AtomicLong> counters = backScriptWithCounters();

        assertThat(limiter.consume(RULE, "church-1", 5).allowed()).isFalse();
        assertThat(total(counters)).isZero();
    }

    @Test
    @DisplayName("A cost below one is a programming error, not a free call")
    void aCostBelowOneIsRefusedOutright() {
        assertThatThrownBy(() -> limiter.consume(RULE, "church-1", 0))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(redis);
    }

    @Test
    @DisplayName("a refused batch does not spend the window: what was left stays available")
    void aRefusedBatchDoesNotConsumeTheWindow() {
        Map<String, AtomicLong> counters = backScriptWithCounters();

        assertThat(limiter.consume(RULE, "church-1", 2).allowed()).isTrue();
        assertThat(limiter.consume(RULE, "church-1", 5).allowed()).isFalse();

        assertThat(limiter.consume(RULE, "church-1", 1).allowed())
                .as("2 of 3 used before the refused batch, so one unit is still free")
                .isTrue();
        assertThat(total(counters)).isEqualTo(3L);
    }

    @Test
    @DisplayName("a call arriving while a refused batch is in flight is judged on what is really left")
    void aConcurrentCallIsNotRefusedByAnInFlightRefusedBatch() throws Exception {
        Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
        CountDownLatch batchInFlight = new CountDownLatch(1);
        CountDownLatch concurrentCallDone = new CountDownLatch(1);
        pauseRefusedBatch(counters, batchInFlight, concurrentCallDone);
        assertThat(limiter.consume(RULE, "church-1", 2).allowed()).isTrue();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<RateLimitDecision> batch = pool.submit(() -> limiter.consume(RULE, "church-1", 5));
            assertThat(batchInFlight.await(5, TimeUnit.SECONDS)).isTrue();

            RateLimitDecision concurrent = limiter.consume(RULE, "church-1", 1);
            concurrentCallDone.countDown();

            assertThat(batch.get(5, TimeUnit.SECONDS).allowed()).isFalse();
            assertThat(concurrent.allowed())
                    .as("2 of 3 used, so the unit asked for alongside the refused batch is free")
                    .isTrue();
        } finally {
            concurrentCallDone.countDown();
            pool.shutdownNow();
        }
    }

    private void whenScriptAnswers(Long answer) {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(answer);
    }

    private void whenScriptFails(RuntimeException failure) {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenThrow(failure);
    }

    /** Stands in for Redis running the script: one indivisible check-then-add per call. */
    private Map<String, AtomicLong> backScriptWithCounters() {
        Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
        pauseRefusedBatch(counters, new CountDownLatch(0), new CountDownLatch(0));
        return counters;
    }

    // Holds the 5-unit batch after its admission and before its return, which is where
    // an add-then-refund limiter exposes the cost it is about to give back.
    private void pauseRefusedBatch(Map<String, AtomicLong> counters,
                                   CountDownLatch batchInFlight, CountDownLatch concurrentCallDone) {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(call -> {
            String key = call.<List<String>>getArgument(1).get(0);
            long cost = Long.parseLong(call.getArgument(2));
            long limit = Long.parseLong(call.getArgument(3));
            long answer;
            synchronized (counters) {
                AtomicLong counter = counters.computeIfAbsent(key, k -> new AtomicLong());
                answer = counter.get() + cost > limit ? RedisRateLimiter.REFUSED : counter.addAndGet(cost);
            }
            if (cost == 5L) {
                batchInFlight.countDown();
                concurrentCallDone.await(5, TimeUnit.SECONDS);
            }
            return answer;
        });
    }

    private RateLimitEvents.CounterUnavailable publishedEvent() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(captor.capture());
        return (RateLimitEvents.CounterUnavailable) captor.getValue();
    }

    private static long total(Map<String, AtomicLong> counters) {
        return counters.values().stream().mapToLong(AtomicLong::get).sum();
    }

    private static long window() {
        return Instant.now().getEpochSecond() / 60;
    }
}
