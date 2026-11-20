package com.ecclesiaflow.platform.journal.jdbc;

import com.ecclesiaflow.platform.journal.JournalLine;
import com.ecclesiaflow.platform.journal.JournalPage;
import com.ecclesiaflow.platform.journal.JournalQuery;
import com.ecclesiaflow.platform.journal.JournalReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/** Filters are bound parameters, never text in the statement; one line more than the page tells whether one follows. */
public class JdbcJournalReader implements JournalReader {

    static final String SELECT_SQL = "SELECT " + JournalLineRowMapper.COLUMNS
            + " FROM platform_journal.journal_line WHERE seq < ?";

    private final JdbcTemplate jdbcTemplate;
    private final JournalLineRowMapper rows;

    public JdbcJournalReader(JdbcTemplate jdbcTemplate, ObjectMapper json) {
        this.jdbcTemplate = jdbcTemplate;
        this.rows = new JournalLineRowMapper(json);
    }

    @Override
    public JournalPage list(JournalQuery query) {
        StringBuilder sql = new StringBuilder(SELECT_SQL);
        List<Object> parameters = new ArrayList<>();
        parameters.add(query.beforeSeq());
        if (query.from() != null) {
            sql.append(" AND occurred_at >= ?");
            parameters.add(Timestamp.from(query.from()));
        }
        if (query.to() != null) {
            sql.append(" AND occurred_at < ?");
            parameters.add(Timestamp.from(query.to()));
        }
        if (query.actorSubject() != null) {
            sql.append(" AND actor_subject = ?");
            parameters.add(query.actorSubject());
        }
        if (query.targetKind() != null) {
            sql.append(" AND target_kind = ?");
            parameters.add(query.targetKind());
        }
        if (query.targetRef() != null) {
            sql.append(" AND target_ref = ?");
            parameters.add(query.targetRef());
        }
        if (query.gesture() != null) {
            sql.append(" AND gesture = ?");
            parameters.add(query.gesture());
        }
        sql.append(" ORDER BY seq DESC LIMIT ?");
        parameters.add(query.limit() + 1);

        List<JournalLine> found = jdbcTemplate.query(sql.toString(), rows, parameters.toArray());
        if (found.size() <= query.limit()) {
            return new JournalPage(found, null);
        }
        List<JournalLine> page = found.subList(0, query.limit());
        return new JournalPage(page, JournalQuery.cursorAfter(page.getLast().seq()));
    }
}
