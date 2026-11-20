package com.ecclesiaflow.platform.journal.jdbc;

import com.ecclesiaflow.platform.journal.JournalEntry;
import com.ecclesiaflow.platform.journal.JournalLine;
import com.ecclesiaflow.platform.journal.PlatformJournal;
import com.ecclesiaflow.platform.journal.events.JournalEvents;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * The JdbcTemplate joins the connection the module's transaction manager holds on the DataSource, so the line commits
 * or rolls back with the gesture. One line per statement: two lines in one insert would take the same position.
 */
public class JdbcPlatformJournal implements PlatformJournal {

    static final String INSERT_SQL = """
            INSERT INTO platform_journal.journal_line
                (occurred_at, actor_subject, actor_roles, actor_session, request_id, gesture, outcome, refusal_code,
                 target_kind, target_ref, case_ref, reason_code, details)
            VALUES (?, ?, CAST(? AS text[]), ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
            RETURNING seq, encode(prev_hash, 'hex') AS prev_hash, encode(row_hash, 'hex') AS row_hash""";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public JdbcPlatformJournal(JdbcTemplate jdbcTemplate, ObjectMapper json, ApplicationEventPublisher events,
                               Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.json = json;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public JournalLine append(JournalEntry entry) {
        requireWritableTransaction();
        // The column's resolution: the line returned is the line stored.
        Instant occurredAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        JournalLine line = jdbcTemplate.queryForObject(INSERT_SQL, (rs, row) -> new JournalLine(rs.getLong("seq"),
                        occurredAt, entry.actor().subject(), entry.actor().roles(), entry.actor().sessionDigest(),
                        entry.requestId(), entry.gesture(), entry.outcome().name(), entry.refusalCode(),
                        entry.targetKind(), entry.targetRef(), entry.caseRef(), entry.reasonCode(), entry.details(),
                        rs.getString("prev_hash"), rs.getString("row_hash")),
                Timestamp.from(occurredAt), entry.actor().subject(), rolesLiteral(entry), entry.actor().sessionDigest(),
                entry.requestId(), entry.gesture(), entry.outcome().name(), entry.refusalCode(), entry.targetKind(),
                entry.targetRef(), entry.caseRef(), entry.reasonCode(), detailsJson(entry));
        events.publishEvent(new JournalEvents.LineAppended(entry.gesture(), entry.outcome()));
        return line;
    }

    // Roles are UPPER_SNAKE codes, checked by JournalActor: nothing in them needs quoting in an array literal.
    private static String rolesLiteral(JournalEntry entry) {
        return "{" + String.join(",", entry.actor().roles()) + "}";
    }

    private String detailsJson(JournalEntry entry) {
        try {
            return json.writeValueAsString(entry.details());
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("A map of strings always serializes", impossible);
        }
    }

    private void requireWritableTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalTransactionStateException(
                    "The journal is written inside the gesture's transaction; no transaction is active");
        }
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalTransactionStateException("The journal cannot be written in a read-only transaction: "
                    + "a reading is journaled too, in a writable one");
        }
        // A transaction on another DataSource would leave the line on a separate, auto-committed connection.
        if (!TransactionSynchronizationManager.hasResource(jdbcTemplate.getDataSource())) {
            throw new IllegalTransactionStateException("The active transaction holds no connection of the journal's "
                    + "DataSource; the line would commit on its own");
        }
    }
}
