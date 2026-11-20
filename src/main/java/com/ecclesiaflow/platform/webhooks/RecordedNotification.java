package com.ecclesiaflow.platform.webhooks;

import java.time.Instant;
import java.util.Objects;

/**
 * A notification as the inbox holds it.
 *
 * @param id       the inbox's own sequence number: what an operator replays, and what a log line may name
 * @param attempts how often handling it failed so far
 */
public record RecordedNotification(long id, InboundNotification notification, Instant receivedAt, int attempts) {

    public RecordedNotification {
        Objects.requireNonNull(notification, "notification");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }
}
