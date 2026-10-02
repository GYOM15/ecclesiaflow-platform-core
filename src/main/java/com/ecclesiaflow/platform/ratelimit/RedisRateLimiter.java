package com.ecclesiaflow.platform.ratelimit;

import com.ecclesiaflow.platform.ratelimit.events.RateLimitEvents;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;

/**
 * A fixed-window counter in Redis: one {@code INCRBY}, an {@code EXPIRE} on the
 * call that created the key, and a {@code DECRBY} giving back a refused call's cost.
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

    /** Namespaced so these counters can never collide with a session or a cache. */
    static final String KEY_PREFIX = "ecclesiaflow:ratelimit:";

    private final StringRedisTemplate redis;
    private final ApplicationEventPublisher events;

    @Override
    public RateLimitDecision consume(RateLimitRule rule, String subject, int cost) {
        if (cost < 1) {
            throw new IllegalArgumentException("cost must be at least 1, was " + cost);
        }
        long windowSeconds = rule.window().getSeconds();
        long now = Instant.now().getEpochSecond();
        long windowNumber = now / windowSeconds;
        String key = KEY_PREFIX + rule.name() + ':' + subject + ':' + windowNumber;

        try {
            Long count = redis.opsForValue().increment(key, cost);
            if (count == null) {
                return unreadable(rule, null);
            }
            // The expiry is set on the call that CREATED the key, which for a
            // batch is the one whose count lands exactly on its own cost.
            if (count == cost) {
                redis.expire(key, Duration.ofSeconds(windowSeconds));
            }
            if (count > rule.limit()) {
                refund(key, cost);
                return RateLimitDecision.refused(rule.limit(), windowSeconds - (now % windowSeconds));
            }
            return RateLimitDecision.allowed(rule.limit(), (int) (rule.limit() - count));
        } catch (RuntimeException e) {
            return unreadable(rule, e);
        }
    }

    // A refused call admitted nothing, so it must not spend the window. Between the
    // increment and this refund a concurrent caller may see the cost and be refused.
    private void refund(String key, int cost) {
        try {
            redis.opsForValue().decrement(key, cost);
        } catch (RuntimeException e) {
            // The refusal stands; the unrefunded cost only lasts until the window ends.
        }
    }

    /**
     * What to do when the counter cannot be read at all.
     *
     * <p>The rule decides, and both answers are defensible for different
     * operations — which is why it is a property of the rule and not of this
     * class. Either way an event is published, so the blind spot is reported
     * rather than looking like a limiter that works.
     */
    private RateLimitDecision unreadable(RateLimitRule rule, RuntimeException cause) {
        events.publishEvent(new RateLimitEvents.CounterUnavailable(rule.name(), rule.failOpen(), cause));
        if (rule.failOpen()) {
            return RateLimitDecision.allowed(rule.limit(), rule.limit());
        }
        return RateLimitDecision.refused(rule.limit(), rule.window().getSeconds());
    }
}
