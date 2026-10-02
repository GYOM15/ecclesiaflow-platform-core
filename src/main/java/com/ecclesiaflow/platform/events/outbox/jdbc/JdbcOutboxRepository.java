package com.ecclesiaflow.platform.events.outbox.jdbc;

import com.ecclesiaflow.platform.events.outbox.OutboxMessage;
import com.ecclesiaflow.platform.events.outbox.relay.ClaimedOutboxMessage;
import com.ecclesiaflow.platform.events.outbox.relay.OutboxRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * A claim leases rows by moving {@code next_attempt_at} instead of holding locks while the batch
 * is published; later writes are fenced on that value, so a relay whose lease ran out changes nothing.
 */
public class JdbcOutboxRepository implements OutboxRepository {

    static final int MAX_ERROR_LENGTH = 1_000;

    // MATERIALIZED keeps PostgreSQL from inlining the locking subquery into the UPDATE.
    static final String CLAIM_SQL = """
            WITH due AS MATERIALIZED (
                SELECT id FROM outbox_event
                WHERE status = 'PENDING' AND next_attempt_at <= ?
                ORDER BY next_attempt_at, id
                LIMIT ?
                FOR UPDATE SKIP LOCKED)
            UPDATE outbox_event o
            SET next_attempt_at = ?
            FROM due
            WHERE o.id = due.id
            RETURNING o.id, o.exchange, o.routing_key, o.payload, o.content_type, o.content_encoding,
                      o.message_id, CAST(o.headers AS text) AS headers, o.attempts""";

    static final String MARK_SENT_SQL = """
            UPDATE outbox_event SET status = 'SENT', sent_at = ?
            WHERE id = ? AND status = 'PENDING'""";

    static final String RESCHEDULE_SQL = """
            UPDATE outbox_event SET attempts = ?, next_attempt_at = ?, last_error = ?
            WHERE id = ? AND status = 'PENDING' AND next_attempt_at = ?""";

    static final String PARK_SQL = """
            UPDATE outbox_event SET status = 'PARKED', attempts = ?, parked_at = ?, last_error = ?
            WHERE id = ? AND status = 'PENDING' AND next_attempt_at = ?""";

    static final String PURGE_SQL = """
            DELETE FROM outbox_event
            WHERE id IN (SELECT id FROM outbox_event WHERE status = 'SENT' AND sent_at < ? LIMIT ?)""";

    static final String COUNT_BY_STATUS_SQL = "SELECT count(*) FROM outbox_event WHERE status = ?";

    static final String OLDEST_PENDING_SQL = "SELECT min(created_at) FROM outbox_event WHERE status = 'PENDING'";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionOperations transactions;

    /** @param transactions {@code REQUIRES_NEW}, so writes commit even on a pool with auto-commit off */
    public JdbcOutboxRepository(JdbcTemplate jdbcTemplate, TransactionOperations transactions) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactions = transactions;
    }

    @Override
    public List<ClaimedOutboxMessage> claimDue(Instant now, Instant leaseUntil, int limit) {
        List<ClaimedOutboxMessage> claimed = transactions.execute(status -> jdbcTemplate.query(CLAIM_SQL,
                JdbcOutboxRepository::claimedRow, Timestamp.from(now), limit, Timestamp.from(leaseUntil)));
        return claimed.stream().sorted(Comparator.comparingLong(ClaimedOutboxMessage::id)).toList();
    }

    @Override
    public void markSent(Collection<Long> ids, Instant sentAt) {
        if (ids.isEmpty()) {
            return;
        }
        Timestamp at = Timestamp.from(sentAt);
        List<Object[]> rows = ids.stream().map(id -> new Object[]{at, id}).toList();
        transactions.executeWithoutResult(status -> jdbcTemplate.batchUpdate(MARK_SENT_SQL, rows));
    }

    @Override
    public void reschedule(long id, Instant leaseUntil, int attempts, Instant nextAttemptAt, String lastError) {
        transactions.executeWithoutResult(status -> jdbcTemplate.update(RESCHEDULE_SQL, attempts,
                Timestamp.from(nextAttemptAt), bounded(lastError), id, Timestamp.from(leaseUntil)));
    }

    @Override
    public void park(long id, Instant leaseUntil, int attempts, Instant parkedAt, String lastError) {
        transactions.executeWithoutResult(status -> jdbcTemplate.update(PARK_SQL, attempts,
                Timestamp.from(parkedAt), bounded(lastError), id, Timestamp.from(leaseUntil)));
    }

    @Override
    public int purgeSent(Instant sentBefore, int limit) {
        return transactions.execute(status -> jdbcTemplate.update(PURGE_SQL, Timestamp.from(sentBefore), limit));
    }

    @Override
    public long countPending() {
        return countByStatus("PENDING");
    }

    @Override
    public long countParked() {
        return countByStatus("PARKED");
    }

    @Override
    public Optional<Instant> oldestPendingCreatedAt() {
        Timestamp oldest = jdbcTemplate.queryForObject(OLDEST_PENDING_SQL, Timestamp.class);
        return Optional.ofNullable(oldest).map(Timestamp::toInstant);
    }

    private long countByStatus(String status) {
        Long count = jdbcTemplate.queryForObject(COUNT_BY_STATUS_SQL, Long.class, status);
        return count == null ? 0 : count;
    }

    private static String bounded(String error) {
        return error == null || error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }

    private static ClaimedOutboxMessage claimedRow(ResultSet rs, int rowNum) throws SQLException {
        OutboxMessage message = new OutboxMessage(
                rs.getString("exchange"),
                rs.getString("routing_key"),
                rs.getBytes("payload"),
                rs.getString("content_type"),
                rs.getString("content_encoding"),
                rs.getString("message_id"),
                HeadersJson.read(rs.getString("headers")));
        return new ClaimedOutboxMessage(rs.getLong("id"), rs.getInt("attempts"), message);
    }
}
