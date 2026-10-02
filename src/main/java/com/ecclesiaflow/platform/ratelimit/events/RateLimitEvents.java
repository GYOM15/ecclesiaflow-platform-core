package com.ecclesiaflow.platform.ratelimit.events;

/**
 * Events published by the rate limiter. The limiter decides and stays silent;
 * {@link RateLimitEventListener} turns these into log lines.
 */
public final class RateLimitEvents {

    private RateLimitEvents() {
    }

    /**
     * The counter could not be read, so the rule's own policy decided the call.
     *
     * @param failOpen whether the call was let through ({@code true}) or refused
     * @param cause    what Redis threw, or {@code null} when it answered without a count
     */
    public record CounterUnavailable(String ruleName, boolean failOpen, Throwable cause) {
    }
}
