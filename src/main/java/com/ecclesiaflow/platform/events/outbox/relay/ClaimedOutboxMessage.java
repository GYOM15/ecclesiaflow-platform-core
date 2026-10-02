package com.ecclesiaflow.platform.events.outbox.relay;

import com.ecclesiaflow.platform.events.outbox.OutboxMessage;

/**
 * A row claimed by the relay.
 *
 * @param attempts failed broker attempts made before this claim
 */
public record ClaimedOutboxMessage(long id, int attempts, OutboxMessage message) {
}
