package com.ecclesiaflow.platform.ratelimit;

import lombok.Getter;

/**
 * Rendered as 429 with {@code Retry-After}: a refusal without a delay teaches clients to retry at once,
 * which turns one limiter into a tight loop.
 */
@Getter
public class RateLimitExceededException extends RuntimeException {

    private final String rule;
    private final long retryAfterSeconds;

    public RateLimitExceededException(String rule, long retryAfterSeconds) {
        super("Rate limit exceeded for " + rule + "; retry after " + retryAfterSeconds + "s");
        this.rule = rule;
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
