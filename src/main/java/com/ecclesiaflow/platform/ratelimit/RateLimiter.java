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
    default RateLimitDecision consume(RateLimitRule rule, String subject) {
        return consume(rule, subject, 1);
    }

    /**
     * Counts {@code cost} calls at once.
     *
     * <p>For an operation whose real unit is not the request. A bulk import is
     * ONE request that mints one invitation and sends one email per row, so
     * counting it as one call let a thousand-row file walk past a two-hundred
     * invitation ceiling — the ceiling counted the wrong thing.
     *
     * <p>All or nothing: if the whole cost would take the subject over the
     * limit, the call is refused and counts nothing. Counting part of a batch
     * and refusing the rest would leave the caller having half-sent something.
     *
     * <p>Abstract so that no implementation can inherit a version that drops the cost.
     *
     * @param cost how many units this call consumes; at least 1
     */
    RateLimitDecision consume(RateLimitRule rule, String subject, int cost);
}
