package com.ecclesiaflow.platform.events.outbox.relay;

import com.ecclesiaflow.platform.events.outbox.OutboxMessage;

/** {@code attempts} counts the failed broker attempts made before this claim. */
public record ClaimedOutboxMessage(long id, int attempts, OutboxMessage message) {
}
