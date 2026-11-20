package com.ecclesiaflow.platform.webhooks.jdbc;

import com.ecclesiaflow.platform.webhooks.InboundNotification;
import com.ecclesiaflow.platform.webhooks.InvalidNotificationException;
import com.ecclesiaflow.platform.webhooks.NotificationAccountDirectory;
import com.ecclesiaflow.platform.webhooks.NotificationReceipt;
import com.ecclesiaflow.platform.webhooks.NotificationVerifier;
import com.ecclesiaflow.platform.webhooks.WebhookTestData;
import com.ecclesiaflow.platform.webhooks.events.WebhookInboxEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JdbcNotificationInboxTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final Map<String, String> HEADERS = Map.of("acme-signature", "t=1,v1=abc");

    /** Runs each callback inline and counts how many transactions were asked for. */
    static final class RecordingTransactions implements TransactionOperations {
        int executions;

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            executions++;
            return action.doInTransaction(new SimpleTransactionStatus());
        }
    }

    /** A verifier of the test provider that hands back what it is told to, or refuses. */
    static final class StubVerifier implements NotificationVerifier {
        InboundNotification answer;
        RuntimeException refusal;

        @Override
        public String provider() {
            return "acme";
        }

        @Override
        public String endpoint() {
            return "accounts";
        }

        @Override
        public InboundNotification verify(byte[] body, Map<String, String> headers) {
            if (refusal != null) {
                throw refusal;
            }
            return answer;
        }
    }

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private NotificationAccountDirectory accounts;
    @Mock
    private ApplicationEventPublisher events;

    private RecordingTransactions transactions;
    private StubVerifier verifier;
    private JdbcNotificationInbox inbox;

    @BeforeEach
    void setUp() {
        transactions = new RecordingTransactions();
        verifier = new StubVerifier();
        inbox = new JdbcNotificationInbox(jdbcTemplate, transactions, accounts, false, events,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @SuppressWarnings("unchecked")
    private void insertAnswers(List<Long> ids) {
        when(jdbcTemplate.query(eq(JdbcNotificationInbox.INSERT_NOTIFICATION_SQL), any(RowMapper.class),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(ids);
    }

    private WebhookInboxEvents.NotificationReceived received() {
        ArgumentCaptor<WebhookInboxEvents.NotificationReceived> event =
                ArgumentCaptor.forClass(WebhookInboxEvents.NotificationReceived.class);
        verify(events).publishEvent(event.capture());
        return event.getValue();
    }

    @Nested
    @DisplayName("receive")
    class Receive {

        @Test
        @DisplayName("refuses a body the verifier refuses before any database work, and counts it")
        void refusesAnUnsignedBody() {
            verifier.refusal = InvalidNotificationException.badSignature(null);

            assertThatThrownBy(() -> inbox.receive(verifier, WebhookTestData.BODY, HEADERS))
                    .isSameAs(verifier.refusal);

            verifyNoInteractions(jdbcTemplate, accounts);
            assertThat(transactions.executions).isZero();
            verify(events).publishEvent(new WebhookInboxEvents.NotificationRejected("acme", "accounts",
                    InvalidNotificationException.Reason.SIGNATURE));
        }

        @Test
        @DisplayName("lets any other failure of the verifier through as the bug it is, uncounted")
        void otherVerifierFailures() {
            verifier.refusal = new IllegalStateException("adapter misconfigured");

            assertThatThrownBy(() -> inbox.receive(verifier, WebhookTestData.BODY, HEADERS))
                    .isSameAs(verifier.refusal);

            verifyNoInteractions(jdbcTemplate, accounts, events);
        }

        @Test
        @DisplayName("refuses a verifier that answers for another provider")
        void providerMismatch() {
            verifier.answer = new InboundNotification("other", "acct_1", "evt_1", "payment.paid", false, null,
                    InboundNotification.digestOf(WebhookTestData.BODY), WebhookTestData.OCCURRED_AT, null);

            assertThatThrownBy(() -> inbox.receive(verifier, WebhookTestData.BODY, HEADERS))
                    .isInstanceOf(IllegalStateException.class);

            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        @DisplayName("answers a notification of the other mode without recording it")
        void otherMode() {
            verifier.answer = WebhookTestData.notification("evt_1", "acct_1", true);

            assertThat(inbox.receive(verifier, WebhookTestData.BODY, HEADERS)).isEqualTo(NotificationReceipt.REFUSED_MODE);

            verifyNoInteractions(jdbcTemplate, accounts);
            assertThat(received()).isEqualTo(new WebhookInboxEvents.NotificationReceived("acme", "accounts",
                    "payment.paid", NotificationReceipt.REFUSED_MODE, null));
        }

        @Test
        @DisplayName("records a notification of a known account once, its work due at once, in one transaction")
        void recordsOnce() {
            verifier.answer = WebhookTestData.notification("evt_1");
            when(accounts.knows("acme", "acct_1")).thenReturn(true);
            insertAnswers(List.of(42L));

            assertThat(inbox.receive(verifier, WebhookTestData.BODY, HEADERS)).isEqualTo(NotificationReceipt.RECORDED);

            assertThat(transactions.executions).isEqualTo(1);
            verify(jdbcTemplate).query(eq(JdbcNotificationInbox.INSERT_NOTIFICATION_SQL), any(RowMapper.class),
                    eq("acme"), eq("acct_1"), eq("evt_1"), eq("payment.paid"), eq(false), eq("obj_1"),
                    eq("2026-10-01"), eq(InboundNotification.digestOf(WebhookTestData.BODY)),
                    eq(Timestamp.from(WebhookTestData.OCCURRED_AT)), eq(Timestamp.from(NOW)));
            verify(jdbcTemplate).update(JdbcNotificationInbox.INSERT_WORK_SQL, 42L, "PENDING", Timestamp.from(NOW));
            assertThat(received()).isEqualTo(new WebhookInboxEvents.NotificationReceived("acme", "accounts",
                    "payment.paid", NotificationReceipt.RECORDED, 42L));
        }

        @Test
        @DisplayName("reads the id the insert returns")
        @SuppressWarnings("unchecked")
        void readsTheReturnedId() throws Exception {
            verifier.answer = WebhookTestData.notification("evt_1");
            when(accounts.knows("acme", "acct_1")).thenReturn(true);
            insertAnswers(List.of(7L));
            inbox.receive(verifier, WebhookTestData.BODY, HEADERS);

            ArgumentCaptor<RowMapper<Long>> mapper = ArgumentCaptor.forClass(RowMapper.class);
            verify(jdbcTemplate).query(eq(JdbcNotificationInbox.INSERT_NOTIFICATION_SQL), mapper.capture(),
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
            ResultSet row = mock(ResultSet.class);
            when(row.getLong(1)).thenReturn(7L);
            assertThat(mapper.getValue().mapRow(row, 0)).isEqualTo(7L);
        }

        @Test
        @DisplayName("answers a redelivery as a duplicate, writing no second work row")
        void redelivery() {
            verifier.answer = WebhookTestData.notification("evt_1");
            when(accounts.knows("acme", "acct_1")).thenReturn(true);
            insertAnswers(List.of());

            assertThat(inbox.receive(verifier, WebhookTestData.BODY, HEADERS)).isEqualTo(NotificationReceipt.DUPLICATE);

            verify(jdbcTemplate, never()).update(eq(JdbcNotificationInbox.INSERT_WORK_SQL), anyLong(), any(), any());
            assertThat(received().notificationId()).isNull();
        }

        @Test
        @DisplayName("sets aside a notification of an unknown account, recorded but never handled")
        void unknownAccount() {
            verifier.answer = WebhookTestData.notification("evt_1", "acct_9", false);
            when(accounts.knows("acme", "acct_9")).thenReturn(false);
            insertAnswers(List.of(43L));

            assertThat(inbox.receive(verifier, WebhookTestData.BODY, HEADERS)).isEqualTo(NotificationReceipt.UNMATCHED);

            verify(jdbcTemplate).update(JdbcNotificationInbox.INSERT_WORK_SQL, 43L, "UNMATCHED", Timestamp.from(NOW));
            assertThat(received().receipt()).isEqualTo(NotificationReceipt.UNMATCHED);
        }
    }

    @Nested
    @DisplayName("replay")
    class Replay {

        @Test
        @DisplayName("makes every parked notification due again, and says how many")
        void replaysParked() {
            when(jdbcTemplate.update(JdbcNotificationInbox.REPLAY_PARKED_SQL, Timestamp.from(NOW))).thenReturn(3);

            assertThat(inbox.replayParked()).isEqualTo(3);

            verify(events).publishEvent(new WebhookInboxEvents.NotificationsReplayed(3));
        }

        @Test
        @DisplayName("makes one parked or unmatched notification due again")
        void replaysOne() {
            when(jdbcTemplate.update(JdbcNotificationInbox.REPLAY_ONE_SQL, Timestamp.from(NOW), 42L)).thenReturn(1);

            assertThat(inbox.replay(42L)).isTrue();

            verify(events).publishEvent(new WebhookInboxEvents.NotificationsReplayed(1));
        }

        @Test
        @DisplayName("replays nothing for a notification that is neither parked nor unmatched")
        void replaysNothing() {
            when(jdbcTemplate.update(JdbcNotificationInbox.REPLAY_ONE_SQL, Timestamp.from(NOW), 42L)).thenReturn(0);

            assertThat(inbox.replay(42L)).isFalse();

            verifyNoInteractions(events);
        }
    }
}
