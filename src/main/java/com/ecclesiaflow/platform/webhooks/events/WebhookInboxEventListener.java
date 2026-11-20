package com.ecclesiaflow.platform.webhooks.events;

import com.ecclesiaflow.platform.logging.SecurityMaskingUtils;
import com.ecclesiaflow.platform.webhooks.NotificationReceipt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;

/**
 * The inbox id is logged unmasked: a technical sequence number, not a person's identifier, and what an operator
 * needs to replay a notification. A rejected body is logged at debug only: a flood of forgeries is read on its
 * counter, not in the log.
 */
@Slf4j
public class WebhookInboxEventListener {

    @EventListener
    public void onNotificationReceived(WebhookInboxEvents.NotificationReceived event) {
        if (event.receipt() == NotificationReceipt.UNMATCHED) {
            log.error("WEBHOOK-INBOX: ❌ {} notification_id={} type={} names an account this module does not know. "
                            + "It is kept and never handled until an operator replays it.",
                    event.provider(), event.notificationId(), type(event.type()));
        } else if (event.receipt() == NotificationReceipt.REFUSED_MODE) {
            log.warn("WEBHOOK-INBOX: ⚠️ {} {} notification type={} is of the other mode than this deployment; "
                    + "not recorded", event.provider(), event.endpoint(), type(event.type()));
        } else {
            log.debug("WEBHOOK-INBOX: {} {} notification type={} {} notification_id={}", event.provider(),
                    event.endpoint(), type(event.type()), event.receipt(), event.notificationId());
        }
    }

    @EventListener
    public void onNotificationRejected(WebhookInboxEvents.NotificationRejected event) {
        log.debug("WEBHOOK-INBOX: Rejected a {} {} body: {}", event.provider(), event.endpoint(), event.reason());
    }

    @EventListener
    public void onNotificationHandled(WebhookInboxEvents.NotificationHandled event) {
        log.debug("WEBHOOK-INBOX: ✅ Handled notification_id={} type={} outcome={} attempt={}",
                event.notificationId(), type(event.type()), event.outcome(), event.attempt());
    }

    @EventListener
    public void onRetryScheduled(WebhookInboxEvents.RetryScheduled event) {
        log.warn("WEBHOOK-INBOX: ⚠️ Handling failed for notification_id={} type={} (attempt {}/{}), "
                        + "retrying at {} - {}",
                event.notificationId(), type(event.type()), event.attempts(), event.maxAttempts(),
                event.nextAttemptAt(), SecurityMaskingUtils.sanitizeInfra(event.reason()));
    }

    @EventListener
    public void onNotificationParked(WebhookInboxEvents.NotificationParked event) {
        log.error("WEBHOOK-INBOX: ❌ Parked notification_id={} type={} after {} attempts - {}. "
                        + "It is not handled until replayed.",
                event.notificationId(), type(event.type()), event.attempts(),
                SecurityMaskingUtils.sanitizeInfra(event.reason()));
    }

    @EventListener
    public void onProcessingCycleFailed(WebhookInboxEvents.ProcessingCycleFailed event) {
        log.error("WEBHOOK-INBOX: ❌ Processing run failed, next run retries - {}",
                SecurityMaskingUtils.sanitizeInfra(event.reason()));
    }

    @EventListener
    public void onNotificationsReplayed(WebhookInboxEvents.NotificationsReplayed event) {
        log.info("WEBHOOK-INBOX: {} notification(s) made due again by an operator", event.count());
    }

    // The type comes from a provider's body: signed, yet never trusted to keep a log line whole.
    private static String type(String type) {
        return SecurityMaskingUtils.escapeControlChars(type);
    }
}
