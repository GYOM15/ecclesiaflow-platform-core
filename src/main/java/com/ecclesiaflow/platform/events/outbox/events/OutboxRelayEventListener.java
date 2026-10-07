package com.ecclesiaflow.platform.events.outbox.events;

import com.ecclesiaflow.platform.logging.SecurityMaskingUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;

/**
 * The outbox id is logged unmasked: a technical sequence number, not a person's identifier, and
 * what an operator needs to replay a parked row.
 */
@Slf4j
public class OutboxRelayEventListener {

    @EventListener
    public void onMessageRelayed(OutboxRelayEvents.MessageRelayed event) {
        log.debug("OUTBOX: ✅ Relayed outbox_id={} routing_key={} attempt={}",
                event.outboxId(), event.routingKey(), event.attempt());
    }

    @EventListener
    public void onRetryScheduled(OutboxRelayEvents.RetryScheduled event) {
        log.warn("OUTBOX: ⚠️ Relay failed for outbox_id={} routing_key={} (attempt {}/{}), retrying at {} - {}",
                event.outboxId(), event.routingKey(), event.attempts(), event.maxAttempts(), event.nextAttemptAt(),
                SecurityMaskingUtils.sanitizeInfra(event.reason()));
    }

    @EventListener
    public void onMessageParked(OutboxRelayEvents.MessageParked event) {
        log.error("OUTBOX: ❌ Parked outbox_id={} routing_key={} after {} attempts - {}. "
                        + "It stays unpublished until replayed (status set back to PENDING).",
                event.outboxId(), event.routingKey(), event.attempts(),
                SecurityMaskingUtils.sanitizeInfra(event.reason()));
    }

    @EventListener
    public void onMessagesDeferred(OutboxRelayEvents.MessagesDeferred event) {
        log.warn("OUTBOX: ⚠️ {} message(s) deferred to {} - {}",
                event.count(), event.nextAttemptAt(), SecurityMaskingUtils.sanitizeInfra(event.reason()));
    }

    @EventListener
    public void onRelayCycleFailed(OutboxRelayEvents.RelayCycleFailed event) {
        log.error("OUTBOX: ❌ {} run failed, next run retries - {}",
                event.task(), SecurityMaskingUtils.sanitizeInfra(event.reason()));
    }

    @EventListener
    public void onSentPurged(OutboxRelayEvents.SentPurged event) {
        log.debug("OUTBOX: Purged {} relayed message(s) sent before {}", event.count(), event.sentBefore());
    }
}
