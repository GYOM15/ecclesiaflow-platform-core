package com.ecclesiaflow.platform.ratelimit;

import java.time.Duration;

/**
 * One limit: how many calls, over how long, counted against whom.
 *
 * @param name           short, stable identifier. It becomes part of the Redis
 *                       key, so renaming it resets every counter in flight
 * @param limit          calls admitted per window
 * @param window         the fixed window the count resets on
 * @param scope          whose calls are counted
 * @param failOpen       what to do when the counter itself cannot be read — see
 *                       {@link #failOpen()}
 */
public record RateLimitRule(
        String name,
        int limit,
        Duration window,
        RateLimitScope scope,
        boolean failOpen) {

    public RateLimitRule {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a rate limit rule needs a name");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1, was " + limit);
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be a positive duration");
        }
        if (scope == null) {
            throw new IllegalArgumentException("a rate limit rule needs a scope");
        }
    }

    /**
     * A rule that lets requests THROUGH when Redis cannot be reached.
     *
     * <p>The right default for the operations this protects. They are already
     * behind authentication and a capability check, so the limiter is a
     * mitigation against abuse or accident by someone who is otherwise entitled
     * — not an authorization decision. Refusing a treasurer's export because a
     * cache is down trades a real outage for a hypothetical abuse.
     */
    public static RateLimitRule perChurch(String name, int limit, Duration window) {
        return new RateLimitRule(name, limit, window, RateLimitScope.PER_CHURCH, true);
    }

    /** As {@link #perChurch}, counted per person instead. */
    public static RateLimitRule perUser(String name, int limit, Duration window) {
        return new RateLimitRule(name, limit, window, RateLimitScope.PER_USER, true);
    }

    /**
     * A rule that REFUSES when Redis cannot be reached.
     *
     * <p>Reserve it for operations whose abuse costs money or cannot be undone —
     * minting a payment session, sending to a whole congregation. There, an
     * outage that admits everything is the worse of the two failures.
     */
    public RateLimitRule failClosed() {
        return new RateLimitRule(name, limit, window, scope, false);
    }
}
