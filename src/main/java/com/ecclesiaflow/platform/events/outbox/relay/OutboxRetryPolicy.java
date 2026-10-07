package com.ecclesiaflow.platform.events.outbox.relay;

import java.time.Duration;

/** Exponential, capped retry delay, and the attempt count at which a message is parked. */
public class OutboxRetryPolicy {

    private final int maxAttempts;
    private final Duration initialBackoff;
    private final double multiplier;
    private final Duration maxBackoff;

    public OutboxRetryPolicy(int maxAttempts, Duration initialBackoff, double multiplier, Duration maxBackoff) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("ecclesiaflow.events.outbox.max-attempts must be at least 1");
        }
        if (initialBackoff == null || initialBackoff.isNegative() || initialBackoff.isZero()) {
            throw new IllegalArgumentException("ecclesiaflow.events.outbox.initial-backoff must be positive");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("ecclesiaflow.events.outbox.backoff-multiplier must be at least 1");
        }
        if (maxBackoff == null || maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException(
                    "ecclesiaflow.events.outbox.max-backoff must be at least the initial backoff");
        }
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.multiplier = multiplier;
        this.maxBackoff = maxBackoff;
    }

    public boolean isExhausted(int attempts) {
        return attempts >= maxAttempts;
    }

    /** Delay before the next try of a message that has now failed {@code attempts} times. */
    public Duration backoffAfter(int attempts) {
        double millis = initialBackoff.toMillis() * Math.pow(multiplier, Math.max(0, attempts - 1));
        return millis >= maxBackoff.toMillis() ? maxBackoff : Duration.ofMillis((long) millis);
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public Duration initialBackoff() {
        return initialBackoff;
    }
}
