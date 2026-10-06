package com.ecclesiaflow.platform.ratelimit.events;

public final class RateLimitEvents {

    private RateLimitEvents() {
    }

    /** {@code cause} is {@code null} when Redis answered without a count. */
    public record CounterUnavailable(String ruleName, boolean failOpen, Throwable cause) {
    }
}
