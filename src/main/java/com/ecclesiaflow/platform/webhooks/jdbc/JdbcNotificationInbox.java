package com.ecclesiaflow.platform.webhooks.jdbc;

import com.ecclesiaflow.platform.webhooks.InboundNotification;
import com.ecclesiaflow.platform.webhooks.InvalidNotificationException;
import com.ecclesiaflow.platform.webhooks.NotificationAccountDirectory;
import com.ecclesiaflow.platform.webhooks.NotificationInbox;
import com.ecclesiaflow.platform.webhooks.NotificationReceipt;
import com.ecclesiaflow.platform.webhooks.NotificationVerifier;
import com.ecclesiaflow.platform.webhooks.events.WebhookInboxEvents;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * Verifies before it touches the database, so a flood of forged bodies holds no connection, and asks the module's
 * directory only once the signature holds. Recording runs in a transaction of its own: a notification and its work
 * row exist together or not at all, and a concurrent redelivery waits on the unique key, then counts as a duplicate.
 */
public class JdbcNotificationInbox implements NotificationInbox {

    static final String INSERT_NOTIFICATION_SQL = """
            INSERT INTO inbound_notification
                (provider, account, event_id, event_type, live, object_ref, api_version, payload_sha256,
                 occurred_at, received_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (provider, account, event_id) DO NOTHING
            RETURNING id""";

    static final String INSERT_WORK_SQL = """
            INSERT INTO inbound_notification_work (notification_id, status, attempts, next_attempt_at)
            VALUES (?, ?, 0, ?)""";

    static final String REPLAY_PARKED_SQL = """
            UPDATE inbound_notification_work
            SET status = 'PENDING', attempts = 0, next_attempt_at = ?, outcome = NULL, last_error = NULL,
                done_at = NULL, parked_at = NULL
            WHERE status = 'PARKED'""";

    static final String REPLAY_ONE_SQL = """
            UPDATE inbound_notification_work
            SET status = 'PENDING', attempts = 0, next_attempt_at = ?, outcome = NULL, last_error = NULL,
                done_at = NULL, parked_at = NULL
            WHERE notification_id = ? AND status IN ('PARKED', 'UNMATCHED')""";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionOperations transactions;
    private final NotificationAccountDirectory accounts;
    private final boolean live;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    /**
     * @param transactions {@code REQUIRES_NEW}, so a notification commits on its own, whatever the caller holds
     * @param live         the deployment's mode: a notification of the other mode is never recorded
     */
    public JdbcNotificationInbox(JdbcTemplate jdbcTemplate, TransactionOperations transactions,
                                 NotificationAccountDirectory accounts, boolean live,
                                 ApplicationEventPublisher events, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactions = transactions;
        this.accounts = accounts;
        this.live = live;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public NotificationReceipt receive(NotificationVerifier verifier, byte[] body, Map<String, String> headers) {
        InboundNotification notification;
        try {
            notification = verifier.verify(body, headers);
        } catch (InvalidNotificationException refused) {
            events.publishEvent(new WebhookInboxEvents.NotificationRejected(verifier.provider(), verifier.endpoint(),
                    refused.reason()));
            throw refused;
        }
        if (!verifier.provider().equals(notification.provider())) {
            throw new IllegalStateException("The " + verifier.provider()
                    + " verifier returned a notification of another provider");
        }
        Recorded recorded = notification.live() != live ? Recorded.REFUSED_MODE : record(notification);
        events.publishEvent(new WebhookInboxEvents.NotificationReceived(verifier.provider(), verifier.endpoint(),
                notification.type(), recorded.receipt(), recorded.notificationId()));
        return recorded.receipt();
    }

    @Override
    public int replayParked() {
        int count = transactions.execute(status ->
                jdbcTemplate.update(REPLAY_PARKED_SQL, Timestamp.from(clock.instant())));
        events.publishEvent(new WebhookInboxEvents.NotificationsReplayed(count));
        return count;
    }

    @Override
    public boolean replay(long notificationId) {
        boolean done = transactions.execute(status ->
                jdbcTemplate.update(REPLAY_ONE_SQL, Timestamp.from(clock.instant()), notificationId)) == 1;
        if (done) {
            events.publishEvent(new WebhookInboxEvents.NotificationsReplayed(1));
        }
        return done;
    }

    private Recorded record(InboundNotification notification) {
        boolean known = accounts.knows(notification.provider(), notification.account());
        Timestamp now = Timestamp.from(clock.instant());
        return transactions.execute(status -> {
            List<Long> ids = jdbcTemplate.query(INSERT_NOTIFICATION_SQL, (rs, row) -> rs.getLong(1),
                    notification.provider(), notification.account(), notification.eventId(), notification.type(),
                    notification.live(), notification.objectRef(), notification.apiVersion(),
                    notification.payloadDigest(), Timestamp.from(notification.occurredAt()), now);
            if (ids.isEmpty()) {
                return Recorded.DUPLICATE;
            }
            long id = ids.getFirst();
            jdbcTemplate.update(INSERT_WORK_SQL, id, known ? "PENDING" : "UNMATCHED", now);
            return new Recorded(known ? NotificationReceipt.RECORDED : NotificationReceipt.UNMATCHED, id);
        });
    }

    private record Recorded(NotificationReceipt receipt, Long notificationId) {
        static final Recorded DUPLICATE = new Recorded(NotificationReceipt.DUPLICATE, null);
        static final Recorded REFUSED_MODE = new Recorded(NotificationReceipt.REFUSED_MODE, null);
    }
}
