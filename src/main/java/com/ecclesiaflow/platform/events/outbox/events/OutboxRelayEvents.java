package com.ecclesiaflow.platform.events.outbox.events;

import java.time.Instant;

/** Reasons are raw broker or database messages: a listener masks them before logging. */
public final class OutboxRelayEvents {

    private OutboxRelayEvents() {
    }

    /** The two scheduled tasks of the relay. */
    public enum Task { RELAY, PURGE }

    /** The broker confirmed and routed a message. {@code attempt} counts this successful one. */
    public record MessageRelayed(long outboxId, String exchange, String routingKey, int attempt) {
    }

    /** A message failed at the broker and will be tried again at {@code nextAttemptAt}. */
    public record RetryScheduled(long outboxId, String exchange, String routingKey, int attempts, int maxAttempts,
                                 Instant nextAttemptAt, String reason) {
    }

    /** A message exhausted its attempts and was set aside; it is not retried until replayed by hand. */
    public record MessageParked(long outboxId, String exchange, String routingKey, int attempts, String reason) {
    }

    /** Messages handed back without an attempt counted: the broker was unreachable or the relay stopped. */
    public record MessagesDeferred(int count, Instant nextAttemptAt, String reason) {
    }

    /** A relay or purge run failed as a whole, usually on the database; the next run retries. */
    public record RelayCycleFailed(Task task, String reason) {
    }

    /** Relayed rows sent before {@code sentBefore} were deleted. */
    public record SentPurged(int count, Instant sentBefore) {
    }
}
