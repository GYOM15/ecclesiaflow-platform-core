package com.ecclesiaflow.platform.security.reauth.code.jdbc;

import com.ecclesiaflow.platform.security.reauth.code.EmailCodeChallenge;
import com.ecclesiaflow.platform.security.reauth.code.EmailCodeOccurrence;
import com.ecclesiaflow.platform.security.reauth.code.EmailCodeStatus;
import com.ecclesiaflow.platform.security.reauth.code.EmailCodeStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The challenges and counts on the tables of {@code db/reauth/reauth_email_code.sql}, in the transaction the
 * service opened: the account's advisory lock and the row locks last until it ends.
 */
public class JdbcEmailCodeStore implements EmailCodeStore {

    // Keeps these locks apart from any other advisory lock of the module's database.
    static final int LOCK_NAMESPACE = 0x52434f44;

    static final String LOCK_SQL = "SELECT count(*) FROM pg_advisory_xact_lock(?, ?)";

    static final String FIND_OPEN_SQL = """
            SELECT id, subject, action, gesture, binding_digest, keyed, code_digest, code_failures, code_issued_at,
                   code_expires_at, resend_available_at, status, created_at, expires_at, closed_at
            FROM reauth_email_code
            WHERE subject = ? AND status = 'PENDING'
            FOR UPDATE""";

    static final String FIND_SQL = """
            SELECT id, subject, action, gesture, binding_digest, keyed, code_digest, code_failures, code_issued_at,
                   code_expires_at, resend_available_at, status, created_at, expires_at, closed_at
            FROM reauth_email_code
            WHERE id = ?
            FOR UPDATE""";

    static final String INSERT_SQL = """
            INSERT INTO reauth_email_code (id, subject, action, gesture, binding_digest, keyed, code_digest,
                                           code_failures, code_issued_at, code_expires_at, resend_available_at,
                                           status, created_at, expires_at, closed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    static final String UPDATE_SQL = """
            UPDATE reauth_email_code
            SET code_digest = ?, code_failures = ?, code_issued_at = ?, code_expires_at = ?,
                resend_available_at = ?, status = ?, closed_at = ?
            WHERE id = ?""";

    static final String CONSUME_SQL = """
            UPDATE reauth_email_code SET status = 'CONSUMED', closed_at = ?
            WHERE id = ? AND status = 'PENDING'""";

    static final String RECORD_SQL = """
            INSERT INTO reauth_email_code_occurrence (subject, kind, challenge_id, occurred_at)
            VALUES (?, ?, ?, ?)""";

    static final String FORGET_SQL = """
            DELETE FROM reauth_email_code_occurrence
            WHERE subject = ? AND kind = ? AND challenge_id = ? AND occurred_at = ?""";

    static final String WINDOW_SQL = """
            SELECT count(*) AS occurrences, min(occurred_at) AS oldest
            FROM reauth_email_code_occurrence
            WHERE subject = ? AND kind = ? AND occurred_at > ?""";

    static final String PURGE_CHALLENGES_SQL = "DELETE FROM reauth_email_code WHERE created_at < ?";

    static final String PURGE_OCCURRENCES_SQL = "DELETE FROM reauth_email_code_occurrence WHERE occurred_at < ?";

    private final JdbcTemplate jdbcTemplate;

    public JdbcEmailCodeStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // A transaction-scoped lock outside a transaction would be released at once.
    @Override
    public void lockAccount(String subject) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalTransactionStateException("The account lock only holds inside a transaction");
        }
        jdbcTemplate.queryForObject(LOCK_SQL, Long.class, LOCK_NAMESPACE, subject.hashCode());
    }

    @Override
    public Optional<EmailCodeChallenge> findOpen(String subject) {
        return first(jdbcTemplate.query(FIND_OPEN_SQL, JdbcEmailCodeStore::challenge, subject));
    }

    @Override
    public Optional<EmailCodeChallenge> findForUpdate(UUID challengeId) {
        return first(jdbcTemplate.query(FIND_SQL, JdbcEmailCodeStore::challenge, challengeId));
    }

    @Override
    public void insert(EmailCodeChallenge challenge) {
        jdbcTemplate.update(INSERT_SQL, challenge.id(), challenge.subject(), challenge.action(), challenge.gesture(),
                challenge.bindingDigest(), challenge.keyed(), challenge.codeDigest(), challenge.codeFailures(),
                timestamp(challenge.codeIssuedAt()), timestamp(challenge.codeExpiresAt()),
                timestamp(challenge.resendAvailableAt()), challenge.status().name(), timestamp(challenge.createdAt()),
                timestamp(challenge.expiresAt()), timestamp(challenge.closedAt()));
    }

    @Override
    public void update(EmailCodeChallenge challenge) {
        int updated = jdbcTemplate.update(UPDATE_SQL, challenge.codeDigest(), challenge.codeFailures(),
                timestamp(challenge.codeIssuedAt()), timestamp(challenge.codeExpiresAt()),
                timestamp(challenge.resendAvailableAt()), challenge.status().name(), timestamp(challenge.closedAt()),
                challenge.id());
        if (updated != 1) {
            throw new IllegalStateException("The challenge " + challenge.id() + " is not stored");
        }
    }

    @Override
    public boolean consume(UUID challengeId, Instant consumedAt) {
        return jdbcTemplate.update(CONSUME_SQL, timestamp(consumedAt), challengeId) == 1;
    }

    @Override
    public void record(String subject, EmailCodeOccurrence occurrence, UUID challengeId, Instant occurredAt) {
        jdbcTemplate.update(RECORD_SQL, subject, occurrence.name(), challengeId, timestamp(occurredAt));
    }

    @Override
    public void forget(String subject, EmailCodeOccurrence occurrence, UUID challengeId, Instant occurredAt) {
        jdbcTemplate.update(FORGET_SQL, subject, occurrence.name(), challengeId, timestamp(occurredAt));
    }

    @Override
    public CounterWindow window(String subject, EmailCodeOccurrence occurrence, Instant since) {
        return jdbcTemplate.queryForObject(WINDOW_SQL, (rs, rowNum) -> new CounterWindow(
                rs.getInt("occurrences"), instant(rs.getTimestamp("oldest"))),
                subject, occurrence.name(), timestamp(since));
    }

    @Override
    public int purgeBefore(Instant cutoff) {
        Timestamp before = timestamp(cutoff);
        return jdbcTemplate.update(PURGE_CHALLENGES_SQL, before) + jdbcTemplate.update(PURGE_OCCURRENCES_SQL, before);
    }

    private static Optional<EmailCodeChallenge> first(List<EmailCodeChallenge> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static EmailCodeChallenge challenge(ResultSet rs, int rowNum) throws SQLException {
        return new EmailCodeChallenge(
                rs.getObject("id", UUID.class),
                rs.getString("subject"),
                rs.getString("action"),
                rs.getString("gesture"),
                rs.getString("binding_digest"),
                rs.getBoolean("keyed"),
                rs.getString("code_digest"),
                rs.getInt("code_failures"),
                instant(rs.getTimestamp("code_issued_at")),
                instant(rs.getTimestamp("code_expires_at")),
                instant(rs.getTimestamp("resend_available_at")),
                EmailCodeStatus.valueOf(rs.getString("status")),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("expires_at")),
                instant(rs.getTimestamp("closed_at")));
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
