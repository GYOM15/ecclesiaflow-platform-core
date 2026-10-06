package com.ecclesiaflow.platform.ratelimit;

import java.time.Duration;

/** {@code name} is part of the Redis key: renaming it resets every counter in flight. */
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
     * Fails open when Redis is unreachable: these operations already pass authentication and a capability
     * check, so the limiter mitigates abuse rather than authorizes, and a cache outage must not refuse them.
     */
    public static RateLimitRule perChurch(String name, int limit, Duration window) {
        return new RateLimitRule(name, limit, window, RateLimitScope.PER_CHURCH, true);
    }

    /** As {@link #perChurch}, counted per person instead. */
    public static RateLimitRule perUser(String name, int limit, Duration window) {
        return new RateLimitRule(name, limit, window, RateLimitScope.PER_USER, true);
    }

    /** Refuses when Redis is unreachable; for operations whose abuse costs money or cannot be undone. */
    public RateLimitRule failClosed() {
        return new RateLimitRule(name, limit, window, scope, false);
    }
}
