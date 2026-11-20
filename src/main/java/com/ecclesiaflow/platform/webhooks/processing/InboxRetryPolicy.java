package com.ecclesiaflow.platform.webhooks.processing;

import java.time.Duration;

/** Doubling, capped delays, and the failure count at which a notification is parked for an operator. */
public class InboxRetryPolicy {

    private final int maxAttempts;
    private final Duration initialBackoff;
    private final double multiplier;
    private final Duration maxBackoff;

    public InboxRetryPolicy(int maxAttempts, Duration initialBackoff, double multiplier, Duration maxBackoff) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("ecclesiaflow.webhooks.inbox.max-attempts must be at least 1");
        }
        if (initialBackoff == null || initialBackoff.isNegative() || initialBackoff.isZero()) {
            throw new IllegalArgumentException("ecclesiaflow.webhooks.inbox.initial-backoff must be positive");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("ecclesiaflow.webhooks.inbox.backoff-multiplier must be at least 1");
        }
        if (maxBackoff == null || maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException(
                    "ecclesiaflow.webhooks.inbox.max-backoff must be at least the initial backoff");
        }
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.multiplier = multiplier;
        this.maxBackoff = maxBackoff;
    }

    public boolean isExhausted(int attempts) {
        return attempts >= maxAttempts;
    }

    /** Delay before the next try of a notification whose handling has now failed {@code attempts} times. */
    public Duration backoffAfter(int attempts) {
        double millis = initialBackoff.toMillis() * Math.pow(multiplier, Math.max(0, attempts - 1));
        return millis >= maxBackoff.toMillis() ? maxBackoff : Duration.ofMillis((long) millis);
    }

    public int maxAttempts() {
        return maxAttempts;
    }
}
