package com.ecclesiaflow.platform.webhooks.jdbc;

import com.ecclesiaflow.platform.webhooks.InboundNotification;
import com.ecclesiaflow.platform.webhooks.RecordedNotification;
import com.ecclesiaflow.platform.webhooks.WebhookTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JdbcInboxRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final Instant LEASE_UNTIL = NOW.plusSeconds(300);

    static final class RecordingTransactions implements TransactionOperations {
        int executions;

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            executions++;
            return action.doInTransaction(new SimpleTransactionStatus());
        }
    }

    @Mock
    private JdbcTemplate jdbcTemplate;

    private RecordingTransactions transactions;
    private JdbcInboxRepository repository;

    @BeforeEach
    void setUp() {
        transactions = new RecordingTransactions();
        repository = new JdbcInboxRepository(jdbcTemplate, transactions);
    }

    private static ResultSet row(long id, int attempts) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("id")).thenReturn(id);
        when(rs.getString("provider")).thenReturn("acme");
        when(rs.getString("account")).thenReturn("acct_1");
        when(rs.getString("event_id")).thenReturn("evt_" + id);
        when(rs.getString("event_type")).thenReturn("payment.paid");
        when(rs.getBoolean("live")).thenReturn(false);
        when(rs.getString("object_ref")).thenReturn("obj_1");
        when(rs.getString("payload_sha256")).thenReturn(InboundNotification.digestOf(WebhookTestData.BODY));
        when(rs.getTimestamp("occurred_at")).thenReturn(Timestamp.from(WebhookTestData.OCCURRED_AT));
        when(rs.getString("api_version")).thenReturn(null);
        when(rs.getTimestamp("received_at")).thenReturn(Timestamp.from(NOW));
        when(rs.getInt("attempts")).thenReturn(attempts);
        return rs;
    }

    @Nested
    @DisplayName("claimDue")
    class ClaimDue {

        @Test
        @DisplayName("leases what is due in one transaction and hands it over oldest first")
        @SuppressWarnings("unchecked")
        void leasesAndOrders() throws Exception {
            ResultSet nineRow = row(9, 2);
            ResultSet fourRow = row(4, 0);
            when(jdbcTemplate.query(eq(JdbcInboxRepository.CLAIM_SQL), any(RowMapper.class),
                    eq(Timestamp.from(NOW)), eq(10), eq(Timestamp.from(LEASE_UNTIL))))
                    .thenAnswer(call -> {
                        RowMapper<RecordedNotification> mapper = call.getArgument(1);
                        return List.of(mapper.mapRow(nineRow, 0), mapper.mapRow(fourRow, 1));
                    });

            List<RecordedNotification> claimed = repository.claimDue(NOW, LEASE_UNTIL, 10);

            assertThat(claimed).extracting(RecordedNotification::id).containsExactly(4L, 9L);
            RecordedNotification nine = claimed.get(1);
            assertThat(nine.attempts()).isEqualTo(2);
            assertThat(nine.receivedAt()).isEqualTo(NOW);
            assertThat(nine.notification().eventId()).isEqualTo("evt_9");
            assertThat(nine.notification().account()).isEqualTo("acct_1");
            assertThat(nine.notification().objectRef()).isEqualTo("obj_1");
            assertThat(nine.notification().apiVersion()).isNull();
            assertThat(nine.notification().occurredAt()).isEqualTo(WebhookTestData.OCCURRED_AT);
            assertThat(transactions.executions).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("outcomes")
    class Outcomes {

        @Test
        @DisplayName("closes a handled notification, fenced on its lease")
        void markDone() {
            repository.markDone(4, LEASE_UNTIL, "APPLIED", NOW);

            verify(jdbcTemplate).update(JdbcInboxRepository.MARK_DONE_SQL, "APPLIED", Timestamp.from(NOW), 4L,
                    Timestamp.from(LEASE_UNTIL));
            assertThat(transactions.executions).isEqualTo(1);
        }

        @Test
        @DisplayName("reschedules a failed notification, fenced on its lease")
        void reschedule() {
            repository.reschedule(4, LEASE_UNTIL, 3, NOW.plusSeconds(120), "IllegalStateException");

            verify(jdbcTemplate).update(JdbcInboxRepository.RESCHEDULE_SQL, 3, Timestamp.from(NOW.plusSeconds(120)),
                    "IllegalStateException", 4L, Timestamp.from(LEASE_UNTIL));
        }

        @Test
        @DisplayName("parks an exhausted notification, its label cut to the column's bound")
        void park() {
            String longLabel = "E".repeat(JdbcInboxRepository.MAX_LABEL_LENGTH + 50);

            repository.park(4, LEASE_UNTIL, 10, NOW, longLabel);

            ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
            verify(jdbcTemplate).update(eq(JdbcInboxRepository.PARK_SQL), args.capture(), args.capture(),
                    args.capture(), args.capture(), args.capture());
            assertThat(args.getAllValues()).containsExactly(10, Timestamp.from(NOW),
                    "E".repeat(JdbcInboxRepository.MAX_LABEL_LENGTH), 4L, Timestamp.from(LEASE_UNTIL));
        }

        @Test
        @DisplayName("keeps a missing label missing")
        void nullLabel() {
            repository.reschedule(4, LEASE_UNTIL, 1, NOW, null);

            verify(jdbcTemplate).update(JdbcInboxRepository.RESCHEDULE_SQL, 1, Timestamp.from(NOW), null, 4L,
                    Timestamp.from(LEASE_UNTIL));
        }
    }

    @Nested
    @DisplayName("gauges")
    class Gauges {

        @Test
        @DisplayName("counts by status, an empty answer as zero")
        void counts() {
            when(jdbcTemplate.queryForObject(JdbcInboxRepository.COUNT_BY_STATUS_SQL, Long.class, "PENDING"))
                    .thenReturn(5L);
            when(jdbcTemplate.queryForObject(JdbcInboxRepository.COUNT_BY_STATUS_SQL, Long.class, "PARKED"))
                    .thenReturn(2L);
            when(jdbcTemplate.queryForObject(JdbcInboxRepository.COUNT_BY_STATUS_SQL, Long.class, "UNMATCHED"))
                    .thenReturn(null);

            assertThat(repository.countPending()).isEqualTo(5);
            assertThat(repository.countParked()).isEqualTo(2);
            assertThat(repository.countUnmatched()).isZero();
        }

        @Test
        @DisplayName("reads the oldest pending arrival, none when nothing waits")
        void oldestPending() {
            when(jdbcTemplate.queryForObject(JdbcInboxRepository.OLDEST_PENDING_SQL, Timestamp.class))
                    .thenReturn(Timestamp.from(NOW), (Timestamp) null);

            assertThat(repository.oldestPendingReceivedAt()).contains(NOW);
            assertThat(repository.oldestPendingReceivedAt()).isEmpty();
        }
    }
}
