package com.ecclesiaflow.platform.ratelimit;

import com.ecclesiaflow.platform.ratelimit.events.RateLimitEvents;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Instant;
import java.util.List;

/**
 * Fixed-window counter admitted by one Lua script: Redis runs it without interleaving, so a refused cost
 * never touches the counter. In Redis because an in-memory counter resets on redeploy and multiplies the
 * limit by the instance count. Up to twice the limit can pass across two adjacent windows, acceptable for
 * a limiter that stops runaway loops rather than metering a paid API.
 */
@RequiredArgsConstructor
public class RedisRateLimiter implements RateLimiter {

    /** Namespaced so these counters can never collide with a session or a cache. */
    static final String KEY_PREFIX = "ecclesiaflow:ratelimit:";

    /** What the script answers for a refused call; an admitted one gets the new count, at least 1. */
    static final long REFUSED = -1L;

    // ARGV: cost, limit, window in seconds. Only a key with no expiry gets one, so a
    // steady stream never extends the window and a key left without one still dies.
    static final RedisScript<Long> CONSUME_SCRIPT = RedisScript.of("""
            local cost = tonumber(ARGV[1])
            local count = tonumber(redis.call('GET', KEYS[1]) or '0')
            if count + cost > tonumber(ARGV[2]) then
              return -1
            end
            count = redis.call('INCRBY', KEYS[1], cost)
            if redis.call('TTL', KEYS[1]) < 0 then
              redis.call('EXPIRE', KEYS[1], ARGV[3])
            end
            return count
            """, Long.class);

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
            // String arguments: the template's serializer only takes strings.
            Long count = redis.execute(CONSUME_SCRIPT, List.of(key),
                    String.valueOf(cost), String.valueOf(rule.limit()), String.valueOf(windowSeconds));
            if (count == null) {
                return unreadable(rule, null);
            }
            if (count == REFUSED) {
                return RateLimitDecision.refused(rule.limit(), windowSeconds - (now % windowSeconds));
            }
            return RateLimitDecision.allowed(rule.limit(), (int) (rule.limit() - count));
        } catch (RuntimeException e) {
            return unreadable(rule, e);
        }
    }

    /** The rule decides; an event is published either way so the blind spot is reported. */
    private RateLimitDecision unreadable(RateLimitRule rule, RuntimeException cause) {
        events.publishEvent(new RateLimitEvents.CounterUnavailable(rule.name(), rule.failOpen(), cause));
        if (rule.failOpen()) {
            return RateLimitDecision.allowed(rule.limit(), rule.limit());
        }
        return RateLimitDecision.refused(rule.limit(), rule.window().getSeconds());
    }
}
