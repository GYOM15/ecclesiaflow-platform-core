package com.ecclesiaflow.platform.events.outbox.jdbc;

import com.ecclesiaflow.platform.events.outbox.OutboxMessage;
import com.ecclesiaflow.platform.events.outbox.relay.ClaimedOutboxMessage;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JdbcOutboxRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");
    private static final Instant LEASE_UNTIL = NOW.plusSeconds(120);

    /** Runs each callback inline and counts how many transactions were asked for. */
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
    private JdbcOutboxRepository repository;

    @BeforeEach
    void setUp() {
        transactions = new RecordingTransactions();
        repository = new JdbcOutboxRepository(jdbcTemplate, transactions);
    }

    private static ResultSet row(long id, int attempts, String headersJson) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("id")).thenReturn(id);
        when(rs.getString("exchange")).thenReturn("ecclesiaflow.domain-events");
        when(rs.getString("routing_key")).thenReturn("rk." + id);
        when(rs.getBytes("payload")).thenReturn(new byte[]{(byte) id});
        when(rs.getString("content_type")).thenReturn("application/x-protobuf");
        when(rs.getString("content_encoding")).thenReturn(null);
        when(rs.getString("message_id")).thenReturn(null);
        when(rs.getString("headers")).thenReturn(headersJson);
        when(rs.getInt("attempts")).thenReturn(attempts);
        return rs;
    }

    @Nested
    @DisplayName("claimDue")
    class ClaimDue {

        @Test
        @DisplayName("Claims due rows with SKIP LOCKED and pushes them out of reach for the lease")
        void claimsWithSkipLocked() {
            when(jdbcTemplate.query(eq(JdbcOutboxRepository.CLAIM_SQL), any(RowMapper.class),
                    eq(Timestamp.from(NOW)), eq(50), eq(Timestamp.from(LEASE_UNTIL)))).thenReturn(List.of());

            assertThat(repository.claimDue(NOW, LEASE_UNTIL, 50)).isEmpty();

            assertThat(JdbcOutboxRepository.CLAIM_SQL)
                    .contains("FOR UPDATE OF candidate SKIP LOCKED")
                    .contains("status = 'PENDING'")
                    .contains("next_attempt_at <= ?")
                    .contains("LIMIT ?");
            assertThat(transactions.executions).isEqualTo(1);
        }

        @Test
        @DisplayName("Claims a keyed row only once no older row of its key is pending or parked")
        void holdsKeyBehindOlderUnsentRow() {
            assertThat(JdbcOutboxRepository.CLAIM_SQL)
                    .contains("candidate.aggregate_key IS NULL OR NOT EXISTS")
                    .contains("older.aggregate_key = candidate.aggregate_key")
                    .contains("older.id < candidate.id")
                    .contains("older.status IN ('PENDING', 'PARKED')");
        }

        @Test
        @DisplayName("Maps every claimed row back to the staged message, oldest id first")
        @SuppressWarnings("unchecked")
        void mapsRowsInIdOrder() throws SQLException {
            ArgumentCaptor<RowMapper<ClaimedOutboxMessage>> mapper = ArgumentCaptor.forClass(RowMapper.class);
            ResultSet seven = row(7, 0, "{\"__TypeId__\":\"a.B\"}");
            ResultSet three = row(3, 2, "{}");
            when(jdbcTemplate.query(eq(JdbcOutboxRepository.CLAIM_SQL), mapper.capture(),
                    eq(Timestamp.from(NOW)), eq(2), eq(Timestamp.from(LEASE_UNTIL))))
                    .thenAnswer(invocation -> {
                        List<ClaimedOutboxMessage> rows = new ArrayList<>();
                        rows.add(mapper.getValue().mapRow(seven, 0));
                        rows.add(mapper.getValue().mapRow(three, 1));
                        return rows;
                    });

            List<ClaimedOutboxMessage> claimed = repository.claimDue(NOW, LEASE_UNTIL, 2);

            assertThat(claimed).extracting(ClaimedOutboxMessage::id).containsExactly(3L, 7L);
            assertThat(claimed.get(0).attempts()).isEqualTo(2);
            assertThat(claimed.get(1).message()).isEqualTo(new OutboxMessage("ecclesiaflow.domain-events",
                    "rk.7", new byte[]{7}, "application/x-protobuf", null, null, Map.of("__TypeId__", "a.B")));
        }

        @Test
        @DisplayName("Tolerates a row whose headers column is null")
        @SuppressWarnings("unchecked")
        void mapsNullHeaders() throws SQLException {
            ArgumentCaptor<RowMapper<ClaimedOutboxMessage>> mapper = ArgumentCaptor.forClass(RowMapper.class);
            ResultSet rs = row(1, 0, null);
            when(jdbcTemplate.query(eq(JdbcOutboxRepository.CLAIM_SQL), mapper.capture(),
                    eq(Timestamp.from(NOW)), eq(1), eq(Timestamp.from(LEASE_UNTIL))))
                    .thenAnswer(invocation -> List.of(mapper.getValue().mapRow(rs, 0)));

            assertThat(repository.claimDue(NOW, LEASE_UNTIL, 1).get(0).message().headers()).isEmpty();
        }
    }

    @Nested
    @DisplayName("outcomes")
    class Outcomes {

        @Test
        @DisplayName("Marks relayed rows sent in one transaction")
        @SuppressWarnings("unchecked")
        void marksSent() {
            ArgumentCaptor<List<Object[]>> args = ArgumentCaptor.forClass(List.class);

            repository.markSent(List.of(4L, 9L), NOW);

            verify(jdbcTemplate).batchUpdate(eq(JdbcOutboxRepository.MARK_SENT_SQL), args.capture());
            assertThat(args.getValue()).containsExactly(
                    new Object[]{Timestamp.from(NOW), 4L},
                    new Object[]{Timestamp.from(NOW), 9L});
            assertThat(JdbcOutboxRepository.MARK_SENT_SQL).contains("status = 'PENDING'");
            assertThat(transactions.executions).isEqualTo(1);
        }

        @Test
        @DisplayName("Does nothing when there is nothing to mark")
        void marksNothing() {
            repository.markSent(List.of(), NOW);

            verifyNoInteractions(jdbcTemplate);
            assertThat(transactions.executions).isZero();
        }

        @Test
        @DisplayName("Reschedules a row only while this relay still holds its lease")
        void reschedulesFenced() {
            repository.reschedule(4L, LEASE_UNTIL, 2, NOW.plusSeconds(10), "broker nack");

            verify(jdbcTemplate).update(JdbcOutboxRepository.RESCHEDULE_SQL, 2, Timestamp.from(NOW.plusSeconds(10)),
                    "broker nack", 4L, Timestamp.from(LEASE_UNTIL));
            assertThat(JdbcOutboxRepository.RESCHEDULE_SQL)
                    .contains("status = 'PENDING'")
                    .contains("next_attempt_at = ?");
            assertThat(transactions.executions).isEqualTo(1);
        }

        @Test
        @DisplayName("Parks a row only while this relay still holds its lease")
        void parksFenced() {
            repository.park(4L, LEASE_UNTIL, 10, NOW, "unroutable");

            verify(jdbcTemplate).update(JdbcOutboxRepository.PARK_SQL, 10, Timestamp.from(NOW), "unroutable", 4L,
                    Timestamp.from(LEASE_UNTIL));
            assertThat(JdbcOutboxRepository.PARK_SQL).contains("status = 'PARKED'");
        }

        @Test
        @DisplayName("Bounds the stored error so one huge message cannot bloat the table")
        void truncatesStoredError() {
            String huge = "x".repeat(5_000);

            repository.reschedule(4L, LEASE_UNTIL, 1, NOW, huge);

            ArgumentCaptor<Object> stored = ArgumentCaptor.forClass(Object.class);
            verify(jdbcTemplate).update(eq(JdbcOutboxRepository.RESCHEDULE_SQL), any(), any(), stored.capture(),
                    any(), any());
            assertThat((String) stored.getValue()).hasSize(JdbcOutboxRepository.MAX_ERROR_LENGTH);
        }

        @Test
        @DisplayName("Stores a null error as null")
        void keepsNullError() {
            repository.park(4L, LEASE_UNTIL, 1, NOW, null);

            verify(jdbcTemplate).update(JdbcOutboxRepository.PARK_SQL, 1, Timestamp.from(NOW), null, 4L,
                    Timestamp.from(LEASE_UNTIL));
        }
    }

    @Nested
    @DisplayName("housekeeping and gauges")
    class Housekeeping {

        @Test
        @DisplayName("Purges relayed rows older than the cutoff in bounded chunks")
        void purgesSent() {
            when(jdbcTemplate.update(JdbcOutboxRepository.PURGE_SQL, Timestamp.from(NOW), 500)).thenReturn(500);

            assertThat(repository.purgeSent(NOW, 500)).isEqualTo(500);
            assertThat(JdbcOutboxRepository.PURGE_SQL).contains("status = 'SENT'").contains("LIMIT ?");
            assertThat(transactions.executions).isEqualTo(1);
        }

        @Test
        @DisplayName("Counts pending and parked rows")
        void counts() {
            when(jdbcTemplate.queryForObject(JdbcOutboxRepository.COUNT_BY_STATUS_SQL, Long.class, "PENDING"))
                    .thenReturn(12L);
            when(jdbcTemplate.queryForObject(JdbcOutboxRepository.COUNT_BY_STATUS_SQL, Long.class, "PARKED"))
                    .thenReturn(null);

            assertThat(repository.countPending()).isEqualTo(12L);
            assertThat(repository.countParked()).isZero();
        }

        @Test
        @DisplayName("Reports when the oldest pending row was staged")
        void oldestPending() {
            when(jdbcTemplate.queryForObject(JdbcOutboxRepository.OLDEST_PENDING_SQL, Timestamp.class))
                    .thenReturn(Timestamp.from(NOW), (Timestamp) null);

            assertThat(repository.oldestPendingCreatedAt()).contains(NOW);
            assertThat(repository.oldestPendingCreatedAt()).isEqualTo(Optional.empty());
        }
    }
}
