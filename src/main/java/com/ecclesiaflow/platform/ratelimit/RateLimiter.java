package com.ecclesiaflow.platform.ratelimit;

public interface RateLimiter {

    /** {@code subject} is a resolved church or user id, never a value the client chose, or the limit is advisory. */
    default RateLimitDecision consume(RateLimitRule rule, String subject) {
        return consume(rule, subject, 1);
    }

    /**
     * For an operation whose unit is not the request, e.g. a bulk import that sends one invitation per row.
     * All or nothing: a {@code cost} (at least 1) that does not fit is refused and counts nothing. Abstract
     * so no implementation can inherit a version that drops the cost.
     */
    RateLimitDecision consume(RateLimitRule rule, String subject, int cost);
}
