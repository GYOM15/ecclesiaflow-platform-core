package com.ecclesiaflow.platform.webhooks.jdbc;

import com.ecclesiaflow.platform.webhooks.InboundNotification;
import com.ecclesiaflow.platform.webhooks.RecordedNotification;
import com.ecclesiaflow.platform.webhooks.processing.InboxRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * A claim leases notifications by moving {@code next_attempt_at} instead of holding locks while they are handled;
 * later writes are fenced on that value, so a processor whose lease ran out changes nothing.
 */
public class JdbcInboxRepository implements InboxRepository {

    static final int MAX_LABEL_LENGTH = 200;

    // MATERIALIZED keeps PostgreSQL from inlining the locking subquery into the UPDATE.
    static final String CLAIM_SQL = """
            WITH due AS MATERIALIZED (
                SELECT candidate.notification_id FROM inbound_notification_work candidate
                WHERE candidate.status = 'PENDING' AND candidate.next_attempt_at <= ?
                ORDER BY candidate.next_attempt_at, candidate.notification_id
                LIMIT ?
                FOR UPDATE OF candidate SKIP LOCKED)
            UPDATE inbound_notification_work w
            SET next_attempt_at = ?
            FROM due, inbound_notification n
            WHERE w.notification_id = due.notification_id AND n.id = w.notification_id
            RETURNING n.id, n.provider, n.account, n.event_id, n.event_type, n.live, n.object_ref, n.api_version,
                      n.payload_sha256, n.occurred_at, n.received_at, w.attempts""";

    static final String MARK_DONE_SQL = """
            UPDATE inbound_notification_work SET status = 'DONE', outcome = ?, done_at = ?, last_error = NULL
            WHERE notification_id = ? AND status = 'PENDING' AND next_attempt_at = ?""";

    static final String RESCHEDULE_SQL = """
            UPDATE inbound_notification_work SET attempts = ?, next_attempt_at = ?, last_error = ?
            WHERE notification_id = ? AND status = 'PENDING' AND next_attempt_at = ?""";

    static final String PARK_SQL = """
            UPDATE inbound_notification_work SET status = 'PARKED', attempts = ?, parked_at = ?, last_error = ?
            WHERE notification_id = ? AND status = 'PENDING' AND next_attempt_at = ?""";

    static final String COUNT_BY_STATUS_SQL = "SELECT count(*) FROM inbound_notification_work WHERE status = ?";

    static final String OLDEST_PENDING_SQL = """
            SELECT min(n.received_at) FROM inbound_notification_work w
            JOIN inbound_notification n ON n.id = w.notification_id
            WHERE w.status = 'PENDING'""";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionOperations transactions;

    /** @param transactions {@code REQUIRES_NEW}, so writes commit even on a pool with auto-commit off */
    public JdbcInboxRepository(JdbcTemplate jdbcTemplate, TransactionOperations transactions) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactions = transactions;
    }

    @Override
    public List<RecordedNotification> claimDue(Instant now, Instant leaseUntil, int limit) {
        List<RecordedNotification> claimed = transactions.execute(status -> jdbcTemplate.query(CLAIM_SQL,
                JdbcInboxRepository::claimedRow, Timestamp.from(now), limit, Timestamp.from(leaseUntil)));
        return claimed.stream().sorted(Comparator.comparingLong(RecordedNotification::id)).toList();
    }

    @Override
    public void markDone(long id, Instant leaseUntil, String outcome, Instant doneAt) {
        transactions.executeWithoutResult(status -> jdbcTemplate.update(MARK_DONE_SQL, bounded(outcome),
                Timestamp.from(doneAt), id, Timestamp.from(leaseUntil)));
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
    public long countPending() {
        return countByStatus("PENDING");
    }

    @Override
    public long countParked() {
        return countByStatus("PARKED");
    }

    @Override
    public long countUnmatched() {
        return countByStatus("UNMATCHED");
    }

    @Override
    public Optional<Instant> oldestPendingReceivedAt() {
        Timestamp oldest = jdbcTemplate.queryForObject(OLDEST_PENDING_SQL, Timestamp.class);
        return Optional.ofNullable(oldest).map(Timestamp::toInstant);
    }

    private long countByStatus(String status) {
        Long count = jdbcTemplate.queryForObject(COUNT_BY_STATUS_SQL, Long.class, status);
        return count == null ? 0 : count;
    }

    private static String bounded(String label) {
        return label == null || label.length() <= MAX_LABEL_LENGTH ? label : label.substring(0, MAX_LABEL_LENGTH);
    }

    private static RecordedNotification claimedRow(ResultSet rs, int rowNum) throws SQLException {
        InboundNotification notification = new InboundNotification(
                rs.getString("provider"),
                rs.getString("account"),
                rs.getString("event_id"),
                rs.getString("event_type"),
                rs.getBoolean("live"),
                rs.getString("object_ref"),
                rs.getString("payload_sha256"),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("api_version"));
        return new RecordedNotification(rs.getLong("id"), notification, rs.getTimestamp("received_at").toInstant(),
                rs.getInt("attempts"));
    }
}
