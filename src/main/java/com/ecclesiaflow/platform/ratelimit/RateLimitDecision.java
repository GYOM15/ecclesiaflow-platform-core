package com.ecclesiaflow.platform.ratelimit;

/** {@code remaining} is never negative; {@code retryAfterSeconds} is 0 when allowed. */
public record RateLimitDecision(boolean allowed, int limit, int remaining, long retryAfterSeconds) {

    public static RateLimitDecision allowed(int limit, int remaining) {
        return new RateLimitDecision(true, limit, Math.max(remaining, 0), 0);
    }

    public static RateLimitDecision refused(int limit, long retryAfterSeconds) {
        return new RateLimitDecision(false, limit, 0, Math.max(retryAfterSeconds, 1));
    }
}
