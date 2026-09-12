package com.ecclesiaflow.platform.ratelimit;

/**
 * What the limiter decided about one call.
 *
 * @param allowed            whether the call may proceed
 * @param limit              the ceiling that applied
 * @param remaining          calls left in this window; never negative
 * @param retryAfterSeconds  how long until the window resets; 0 when allowed
 */
public record RateLimitDecision(boolean allowed, int limit, int remaining, long retryAfterSeconds) {

    /** The answer for a call that is let through. */
    public static RateLimitDecision allowed(int limit, int remaining) {
        return new RateLimitDecision(true, limit, Math.max(remaining, 0), 0);
    }

    /** The answer for a call that is refused, carrying when to come back. */
    public static RateLimitDecision refused(int limit, long retryAfterSeconds) {
        return new RateLimitDecision(false, limit, 0, Math.max(retryAfterSeconds, 1));
    }
}
