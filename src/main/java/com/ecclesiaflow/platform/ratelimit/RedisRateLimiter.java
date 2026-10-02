package com.ecclesiaflow.platform.ratelimit;

import com.ecclesiaflow.platform.ratelimit.events.RateLimitEvents;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Instant;
import java.util.List;

/**
 * A fixed-window counter in Redis, admitted by one Lua script: the cost is added
 * only if it fits under the limit, and the window's expiry is set on the write
 * that created the key.
 *
 * <p><strong>Why a script.</strong> Adding first and giving a refused cost back
 * afterwards left a gap in which a concurrent caller saw that cost and was refused
 * although the window still had room. Redis runs a script without interleaving
 * any other command, so a refused call never touches the counter.
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
