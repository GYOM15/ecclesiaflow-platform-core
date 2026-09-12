package com.ecclesiaflow.platform.ratelimit;

/**
 * Counts one call against a rule. The only way a limit is enforced.
 *
 * <p>A port rather than a concrete class so a module can test its own wiring
 * without a Redis, and so the counting strategy can change without touching a
 * single controller.
 */
public interface RateLimiter {

    /**
     * Counts one call by {@code subject} against {@code rule}.
     *
     * @param rule    the limit being applied
     * @param subject who is being counted — a church id or a user id, already
     *                resolved; never anything the CLIENT chose, or the limit is
     *                advisory
     * @return whether the call may proceed, and when to come back if not
     */
    RateLimitDecision consume(RateLimitRule rule, String subject);
}
