package com.ecclesiaflow.platform.events.retry.events;

import java.time.Duration;

/** Reasons are raw exception messages: a listener masks them before logging. */
public final class DelayedRetryEvents {

    private DelayedRetryEvents() {
    }

    public enum DeadLetterCause {
        /** Every stage was tried, or the queue has none. */
        EXHAUSTED,
        /** An unreadable message or an explicit reject: no wait can change the outcome. */
        NOT_RETRYABLE,
        /** The broker did not take the copy for the next stage. */
        PUBLISH_FAILED
    }

    /** A failed delivery waits {@code delay} in {@code retryQueue} before attempt {@code attempt} of {@code stages}. */
    public record RetryScheduled(String queue, int attempt, int stages, Duration delay, String retryQueue,
                                 String reason) {
    }

    /** A delivery goes to the dead-letter queue after {@code retries} delayed retries. */
    public record DeadLettered(String queue, DeadLetterCause cause, int retries, String reason) {
    }
}
