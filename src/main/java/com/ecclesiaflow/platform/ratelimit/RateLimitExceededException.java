package com.ecclesiaflow.platform.ratelimit;

import lombok.Getter;

/**
 * Thrown when a caller has spent its allowance. Rendered as {@code 429} with a
 * {@code Retry-After} header by each module's exception handler.
 *
 * <p>It carries the wait rather than leaving the client to guess: a refusal with
 * no delay attached teaches clients to retry immediately, which turns one
 * limiter into a tight loop.
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
