package com.ecclesiaflow.platform.webhooks.processing;

import com.ecclesiaflow.platform.webhooks.InboundNotificationHandler;
import com.ecclesiaflow.platform.webhooks.RecordedNotification;
import com.ecclesiaflow.platform.webhooks.events.WebhookInboxEvents;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Hands each claimed notification to the module's handler outside any transaction, one after the other, and
 * records what became of it. A failing notification is retried later and the batch goes on: one stuck
 * notification never holds back the others.
 */
public class InboxProcessor {

    static final String DEFAULT_OUTCOME = "HANDLED";
    static final int MAX_OUTCOME_LENGTH = 64;

    private final InboxRepository repository;
    private final InboundNotificationHandler handler;
    private final InboxRetryPolicy retryPolicy;
    private final Settings settings;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    /** The lease must outlast the handling of a whole batch, or another processor could take it over meanwhile. */
    public record Settings(int batchSize, Duration lease) {

        public Settings {
            if (batchSize < 1) {
                throw new IllegalArgumentException("ecclesiaflow.webhooks.inbox.batch-size must be at least 1");
            }
            if (lease == null || lease.isNegative() || lease.isZero()) {
                throw new IllegalArgumentException("ecclesiaflow.webhooks.inbox.lease must be positive");
            }
        }
    }

    public InboxProcessor(InboxRepository repository, InboundNotificationHandler handler,
                          InboxRetryPolicy retryPolicy, Settings settings, ApplicationEventPublisher events,
                          Clock clock) {
        this.repository = repository;
        this.handler = handler;
        this.retryPolicy = retryPolicy;
        this.settings = settings;
        this.events = events;
        this.clock = clock;
    }

    public ProcessingBatchResult processDue() {
        // Millisecond precision so the lease written by the claim compares equal when it fences later writes.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Instant leaseUntil = now.plus(settings.lease());
        List<RecordedNotification> claimed = repository.claimDue(now, leaseUntil, settings.batchSize());
        if (claimed.isEmpty()) {
            return ProcessingBatchResult.EMPTY;
        }
        int handled = 0;
        int retried = 0;
        int parked = 0;
        for (RecordedNotification notification : claimed) {
            String outcome;
            try {
                outcome = handler.handle(notification);
            } catch (RuntimeException failure) {
                if (failed(notification, leaseUntil, failure)) {
                    parked++;
                } else {
                    retried++;
                }
                continue;
            }
            String label = label(outcome);
            repository.markDone(notification.id(), leaseUntil, label, clock.instant());
            events.publishEvent(new WebhookInboxEvents.NotificationHandled(notification.id(),
                    notification.notification().provider(), notification.notification().type(), label,
                    notification.attempts() + 1));
            handled++;
        }
        return new ProcessingBatchResult(claimed.size(), handled, retried, parked,
                claimed.size() == settings.batchSize());
    }

    // The row keeps the exception's type only: a message can quote the provider's body. The event carries it
    // whole, for a listener that masks it.
    private boolean failed(RecordedNotification notification, Instant leaseUntil, RuntimeException failure) {
        int attempts = notification.attempts() + 1;
        String type = failure.getClass().getSimpleName();
        String reason = type + ": " + failure.getMessage();
        String provider = notification.notification().provider();
        String eventType = notification.notification().type();
        if (retryPolicy.isExhausted(attempts)) {
            repository.park(notification.id(), leaseUntil, attempts, clock.instant(), type);
            events.publishEvent(new WebhookInboxEvents.NotificationParked(notification.id(), provider, eventType,
                    attempts, reason));
            return true;
        }
        Instant nextAttemptAt = clock.instant().plus(retryPolicy.backoffAfter(attempts));
        repository.reschedule(notification.id(), leaseUntil, attempts, nextAttemptAt, type);
        events.publishEvent(new WebhookInboxEvents.RetryScheduled(notification.id(), provider, eventType, attempts,
                retryPolicy.maxAttempts(), nextAttemptAt, reason));
        return false;
    }

    private static String label(String outcome) {
        if (outcome == null || outcome.isBlank()) {
            return DEFAULT_OUTCOME;
        }
        return outcome.length() <= MAX_OUTCOME_LENGTH ? outcome : outcome.substring(0, MAX_OUTCOME_LENGTH);
    }
}
