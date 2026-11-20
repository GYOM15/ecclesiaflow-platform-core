package com.ecclesiaflow.platform.webhooks.events;

import com.ecclesiaflow.platform.webhooks.InvalidNotificationException;
import com.ecclesiaflow.platform.webhooks.NotificationReceipt;

import java.time.Instant;

/**
 * No event names a provider's event id or account: those identify a payer's objects at the provider. The inbox
 * id is the inbox's own sequence number, what an operator replays. Reasons are raw exception messages: a listener
 * masks them before logging.
 */
public final class WebhookInboxEvents {

    private WebhookInboxEvents() {
    }

    /** A verified notification was answered. {@code notificationId} is null when nothing was recorded. */
    public record NotificationReceived(String provider, String endpoint, String type, NotificationReceipt receipt,
                                       Long notificationId) {
    }

    /** A body the verifier refused; nothing was written. */
    public record NotificationRejected(String provider, String endpoint, InvalidNotificationException.Reason reason) {
    }

    /** The handler handled a notification. {@code attempt} counts this successful one. */
    public record NotificationHandled(long notificationId, String provider, String type, String outcome,
                                      int attempt) {
    }

    /** The handler failed; the notification is tried again at {@code nextAttemptAt}. */
    public record RetryScheduled(long notificationId, String provider, String type, int attempts, int maxAttempts,
                                 Instant nextAttemptAt, String reason) {
    }

    /** The handler failed {@code attempts} times in a row; the notification waits for an operator. */
    public record NotificationParked(long notificationId, String provider, String type, int attempts,
                                     String reason) {
    }

    /** A processing run failed as a whole, usually on the database; the next run retries. */
    public record ProcessingCycleFailed(String reason) {
    }

    /** An operator made {@code count} set-aside notifications due again. */
    public record NotificationsReplayed(int count) {
    }
}
