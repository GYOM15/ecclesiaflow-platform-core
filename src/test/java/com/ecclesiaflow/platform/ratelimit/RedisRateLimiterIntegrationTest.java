package com.ecclesiaflow.platform.ratelimit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The admission script on a real Redis: what it writes, what it refuses to write, and its expiry. */
class RedisRateLimiterIntegrationTest {

    private static final Duration WINDOW = Duration.ofHours(1);

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory connections;
    private static StringRedisTemplate redis;

    private final List<Object> published = new CopyOnWriteArrayList<>();
    private RedisRateLimiter limiter;
    private String subject;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
        connections = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connections.afterPropertiesSet();
        connections.start();
        redis = new StringRedisTemplate(connections);
    }

    @AfterAll
    static void stopRedis() {
        connections.destroy();
        REDIS.stop();
    }

    @BeforeEach
    void setUp() {
        limiter = new RedisRateLimiter(redis, published::add);
        subject = "church-" + UUID.randomUUID();
    }

    @Test
    @DisplayName("a refused batch leaves the counter exactly where it was")
    void aRefusedBatchLeavesTheCounterUntouched() {
        RateLimitRule rule = rule(3);

        assertThat(limiter.consume(rule, subject, 2).allowed()).isTrue();
        assertThat(limiter.consume(rule, subject, 5).allowed()).isFalse();
        assertThat(redis.opsForValue().get(key(rule))).isEqualTo("2");

        RateLimitDecision last = limiter.consume(rule, subject, 1);
        assertThat(last.allowed()).isTrue();
        assertThat(last.remaining()).isZero();
        assertThat(redis.opsForValue().get(key(rule))).isEqualTo("3");
        assertThat(published).isEmpty();
    }

    @Test
    @DisplayName("a batch refused on an empty window does not even create the key")
    void aRefusedBatchCreatesNoKey() {
        RateLimitRule rule = rule(3);

        assertThat(limiter.consume(rule, subject, 5).allowed()).isFalse();

        assertThat(redis.hasKey(key(rule))).isFalse();
    }

    @Test
    @DisplayName("the expiry is set on the first write and never pushed back by later ones")
    void theExpiryIsSetOnceAndNeverExtended() throws InterruptedException {
        RateLimitRule rule = rule(10);

        limiter.consume(rule, subject);
        Long first = redis.getExpire(key(rule), TimeUnit.MILLISECONDS);
        Thread.sleep(50);
        limiter.consume(rule, subject);
        Long second = redis.getExpire(key(rule), TimeUnit.MILLISECONDS);

        assertThat(first).isPositive().isLessThanOrEqualTo(WINDOW.toMillis());
        assertThat(second).isPositive().isLessThan(first);
    }

    @Test
    @DisplayName("a counter left without an expiry gets one on its next admission")
    void aKeyWithoutExpiryGetsOne() {
        RateLimitRule rule = rule(10);
        redis.opsForValue().set(key(rule), "1");

        RateLimitDecision decision = limiter.consume(rule, subject);

        assertThat(decision.remaining()).isEqualTo(8);
        assertThat(redis.getExpire(key(rule), TimeUnit.SECONDS)).isPositive();
    }

    @Test
    @DisplayName("refused batches hammering a window never cost a single unit its room")
    void concurrentRefusedBatchesNeverStealRoom() throws Exception {
        RateLimitRule rule = rule(100);
        assertThat(limiter.consume(rule, subject, 60).allowed()).isTrue();

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<RateLimitDecision>>> batches = new ArrayList<>();
        List<Future<List<RateLimitDecision>>> units = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                batches.add(pool.submit(repeat(start, 200, () -> limiter.consume(rule, subject, 41))));
                units.add(pool.submit(repeat(start, 10, () -> limiter.consume(rule, subject, 1))));
            }
            start.countDown();

            assertThat(decisions(batches)).hasSize(800).noneMatch(RateLimitDecision::allowed);
            assertThat(decisions(units))
                    .as("the 40 units fit in what the first 60 left")
                    .hasSize(40).allMatch(RateLimitDecision::allowed);
            assertThat(redis.opsForValue().get(key(rule))).isEqualTo("100");
        } finally {
            pool.shutdownNow();
        }
    }

    private static Callable<List<RateLimitDecision>> repeat(CountDownLatch start, int times,
                                                           Callable<RateLimitDecision> call) {
        return () -> {
            start.await();
            List<RateLimitDecision> decisions = new ArrayList<>();
            for (int i = 0; i < times; i++) {
                decisions.add(call.call());
            }
            return decisions;
        };
    }

    private static List<RateLimitDecision> decisions(List<Future<List<RateLimitDecision>>> futures)
            throws Exception {
        List<RateLimitDecision> all = new ArrayList<>();
        for (Future<List<RateLimitDecision>> future : futures) {
            all.addAll(future.get(30, TimeUnit.SECONDS));
        }
        return all;
    }

    private static RateLimitRule rule(int limit) {
        return RateLimitRule.perChurch("it-" + limit, limit, WINDOW);
    }

    private String key(RateLimitRule rule) {
        long windowNumber = Instant.now().getEpochSecond() / WINDOW.getSeconds();
        return RedisRateLimiter.KEY_PREFIX + rule.name() + ':' + subject + ':' + windowNumber;
    }
}
