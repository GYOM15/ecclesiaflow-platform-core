package com.ecclesiaflow.platform.webhooks.events;

import com.ecclesiaflow.platform.webhooks.InvalidNotificationException;
import com.ecclesiaflow.platform.webhooks.NotificationReceipt;
import nl.altindag.log.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookInboxEventListenerTest {

    private static final Instant AT = Instant.parse("2026-11-20T10:02:00Z");

    private final LogCaptor logs = LogCaptor.forClass(WebhookInboxEventListener.class);
    private final WebhookInboxEventListener listener = new WebhookInboxEventListener();

    @AfterEach
    void close() {
        logs.close();
    }

    @Test
    @DisplayName("an unmatched notification is an error naming the inbox id an operator replays, never the account")
    void unmatched() {
        listener.onNotificationReceived(new WebhookInboxEvents.NotificationReceived("acme", "accounts",
                "payment.paid", NotificationReceipt.UNMATCHED, 43L));

        assertThat(logs.getErrorLogs()).singleElement().asString()
                .contains("acme", "notification_id=43", "type=payment.paid", "until an operator replays it");
    }

    @Test
    @DisplayName("a notification of the other mode is a warning")
    void otherMode() {
        listener.onNotificationReceived(new WebhookInboxEvents.NotificationReceived("acme", "accounts",
                "payment.paid", NotificationReceipt.REFUSED_MODE, null));

        assertThat(logs.getWarnLogs()).singleElement().asString().contains("other mode", "acme accounts");
    }

    @Test
    @DisplayName("a recorded, duplicate, rejected or handled notification is debug only")
    void routineAtDebug() {
        logs.setLogLevelToDebug();

        listener.onNotificationReceived(new WebhookInboxEvents.NotificationReceived("acme", "accounts",
                "payment.paid", NotificationReceipt.RECORDED, 42L));
        listener.onNotificationReceived(new WebhookInboxEvents.NotificationReceived("acme", "accounts",
                "payment.paid", NotificationReceipt.DUPLICATE, null));
        listener.onNotificationRejected(new WebhookInboxEvents.NotificationRejected("acme", "platform",
                InvalidNotificationException.Reason.SIGNATURE));
        listener.onNotificationHandled(new WebhookInboxEvents.NotificationHandled(42, "acme", "payment.paid",
                "APPLIED", 1));

        assertThat(logs.getDebugLogs()).hasSize(4).anySatisfy(line -> assertThat(line).contains("SIGNATURE"));
        assertThat(logs.getWarnLogs()).isEmpty();
        assertThat(logs.getErrorLogs()).isEmpty();
    }

    @Test
    @DisplayName("a retry and a parking say why, with what the reason holds of a person or a host masked")
    void failuresMasked() {
        String reason = "IllegalStateException: payer jane.doe@example.com unreachable at api.acme.test:443";

        listener.onRetryScheduled(new WebhookInboxEvents.RetryScheduled(42, "acme", "payment.paid", 3, 10, AT,
                reason));
        listener.onNotificationParked(new WebhookInboxEvents.NotificationParked(42, "acme", "payment.paid", 10,
                reason));
        listener.onProcessingCycleFailed(new WebhookInboxEvents.ProcessingCycleFailed(
                "CannotGetJdbcConnectionException: jdbc:postgresql://payments-db:5432/x"));

        assertThat(logs.getWarnLogs()).singleElement().asString().contains("notification_id=42", "(attempt 3/10)");
        assertThat(logs.getErrorLogs()).hasSize(2);
        assertThat(logs.getLogs()).allSatisfy(line -> assertThat(line)
                .doesNotContain("jane.doe@example.com", "api.acme.test", "payments-db"));
    }

    @Test
    @DisplayName("a provider's type cannot forge a second log line")
    void typeEscaped() {
        listener.onNotificationReceived(new WebhookInboxEvents.NotificationReceived("acme", "accounts",
                "payment.paid\nWEBHOOK-INBOX: forged", NotificationReceipt.UNMATCHED, 43L));

        assertThat(logs.getErrorLogs()).singleElement().asString().doesNotContain("\n").contains("\\n");
    }

    @Test
    @DisplayName("an operator's replay is recorded at info")
    void replayed() {
        listener.onNotificationsReplayed(new WebhookInboxEvents.NotificationsReplayed(3));

        assertThat(logs.getInfoLogs()).singleElement().asString().contains("3 notification(s)");
    }
}
