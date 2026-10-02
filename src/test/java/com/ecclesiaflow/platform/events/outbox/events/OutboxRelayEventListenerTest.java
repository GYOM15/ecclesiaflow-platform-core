package com.ecclesiaflow.platform.events.outbox.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;

/** The listener only turns events into log lines; these tests pin that no event shape can break it. */
class OutboxRelayEventListenerTest {

    private static final Instant AT = Instant.parse("2026-10-02T10:00:00Z");

    private final OutboxRelayEventListener listener = new OutboxRelayEventListener();

    @Test
    @DisplayName("Logs every relay event, including ones without a reason")
    void handlesEveryEvent() {
        assertThatCode(() -> {
            listener.onMessageRelayed(new OutboxRelayEvents.MessageRelayed(1, "ex", "rk", 1));
            listener.onRetryScheduled(new OutboxRelayEvents.RetryScheduled(1, "ex", "rk", 2, 10, AT,
                    "AmqpIOException: broker at rabbitmq.internal:5672 closed"));
            listener.onRetryScheduled(new OutboxRelayEvents.RetryScheduled(1, "ex", "rk", 2, 10, AT, null));
            listener.onMessageParked(new OutboxRelayEvents.MessageParked(1, "ex", "rk", 10, "unroutable: 312 NO_ROUTE"));
            listener.onMessagesDeferred(new OutboxRelayEvents.MessagesDeferred(3, AT, "Connection refused"));
            listener.onRelayCycleFailed(new OutboxRelayEvents.RelayCycleFailed(
                    OutboxRelayEvents.Task.RELAY, "CannotGetJdbcConnectionException: jdbc:postgresql://db:5432/x"));
            listener.onSentPurged(new OutboxRelayEvents.SentPurged(1_000, AT));
        }).doesNotThrowAnyException();
    }
}
