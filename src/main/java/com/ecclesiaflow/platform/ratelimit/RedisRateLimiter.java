package com.ecclesiaflow.platform.ratelimit;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;

/**
 * A fixed-window counter in Redis: one {@code INCR}, and an {@code EXPIRE} on
 * the call that created the key.
 *
 * <p><strong>Why the expiry is set only on the first hit.</strong> Refreshing it
 * on every call would let a steady stream hold the window open for ever, so the
 * counter would never reset and a caller would stay locked out permanently after
 * a single burst. The key must die on its own schedule, not on the caller's.
 *
 * <p><strong>Why Redis and not a map.</strong> An in-memory counter resets on
 * every redeploy and counts separately on each instance, so N instances multiply
 * every limit by N. The landing app carried exactly that on its contact form
 * before this existed.
 *
 * <p>The key embeds the window number rather than relying on a sliding
 * structure: a division of the epoch second by the window length. It costs one
 * round trip, and the boundary effect it allows — up to twice the limit across
 * two adjacent windows — is a price worth paying here, where the limit exists to
 * stop runaway loops and accidental fan-out rather than to meter a paid API.
 */
@RequiredArgsConstructor
public class RedisRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    /** Namespaced so these counters can never collide with a session or a cache. */
    static final String KEY_PREFIX = "ecclesiaflow:ratelimit:";

    private final StringRedisTemplate redis;

    @Override
    public RateLimitDecision consume(RateLimitRule rule, String subject) {
        long windowSeconds = rule.window().getSeconds();
        long now = Instant.now().getEpochSecond();
        long windowNumber = now / windowSeconds;
        String key = KEY_PREFIX + rule.name() + ':' + subject + ':' + windowNumber;

        try {
            Long count = redis.opsForValue().increment(key);
            if (count == null) {
                return unreadable(rule, "Redis returned no count");
            }
            if (count == 1L) {
                redis.expire(key, Duration.ofSeconds(windowSeconds));
            }
            if (count > rule.limit()) {
                return RateLimitDecision.refused(rule.limit(), windowSeconds - (now % windowSeconds));
            }
            return RateLimitDecision.allowed(rule.limit(), (int) (rule.limit() - count));
        } catch (RuntimeException e) {
            return unreadable(rule, e.toString());
        }
    }

    /**
     * What to do when the counter cannot be read at all.
     *
     * <p>The rule decides, and both answers are defensible for different
     * operations — which is why it is a property of the rule and not of this
     * class. Either way it is logged at WARN: a limiter silently doing nothing
     * is indistinguishable from a limiter working, and that is how an outage
     * goes unnoticed for weeks.
     */
    private RateLimitDecision unreadable(RateLimitRule rule, String cause) {
        if (rule.failOpen()) {
            log.warn("Rate limit '{}' not enforced — the counter could not be read: {}",
                    rule.name(), cause);
            return RateLimitDecision.allowed(rule.limit(), rule.limit());
        }
        log.warn("Rate limit '{}' refusing — the counter could not be read: {}",
                rule.name(), cause);
        return RateLimitDecision.refused(rule.limit(), rule.window().getSeconds());
    }
}
