package com.ecclesiaflow.platform.journal.jdbc;

import com.ecclesiaflow.platform.journal.JournalHead;
import com.ecclesiaflow.platform.journal.JournalLine;
import com.ecclesiaflow.platform.journal.JournalRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

/** The application role reads the head through the owner's function: it holds no right on the head's table. */
public class JdbcJournalRepository implements JournalRepository {

    static final String HEAD_SQL = """
            SELECT last_seq, encode(last_hash, 'hex') AS last_hash, start_seq, encode(start_hash, 'hex') AS start_hash
            FROM platform_journal.current_head()""";

    static final String LINES_SQL = "SELECT " + JournalLineRowMapper.COLUMNS
            + " FROM platform_journal.journal_line WHERE seq > ? AND seq <= ? ORDER BY seq LIMIT ?";

    static final String ROW_HASH_SQL =
            "SELECT encode(row_hash, 'hex') FROM platform_journal.journal_line WHERE seq = ?";

    static final String PURGE_SQL = "SELECT platform_journal.purge_expired()";

    private final JdbcTemplate jdbcTemplate;
    private final JournalLineRowMapper rows;

    public JdbcJournalRepository(JdbcTemplate jdbcTemplate, ObjectMapper json) {
        this.jdbcTemplate = jdbcTemplate;
        this.rows = new JournalLineRowMapper(json);
    }

    @Override
    public JournalHead head() {
        List<JournalHead> heads = jdbcTemplate.query(HEAD_SQL, (rs, row) -> new JournalHead(rs.getLong("last_seq"),
                rs.getString("last_hash"), rs.getLong("start_seq"), rs.getString("start_hash")));
        if (heads.size() != 1) {
            throw new IllegalStateException("The journal has " + heads.size() + " heads; its SQL writes exactly one");
        }
        return heads.getFirst();
    }

    @Override
    public List<JournalLine> lines(long afterSeq, long throughSeq, int limit) {
        return jdbcTemplate.query(LINES_SQL, rows, afterSeq, throughSeq, limit);
    }

    @Override
    public Optional<String> rowHashAt(long seq) {
        return jdbcTemplate.queryForList(ROW_HASH_SQL, String.class, seq).stream().findFirst();
    }

    // The function locks the lines exclusively until its transaction ends: called inside a longer one, every gesture
    // of the module waits for that one.
    @Override
    public long purgeExpired() {
        Long removed = jdbcTemplate.queryForObject(PURGE_SQL, Long.class);
        return removed == null ? 0 : removed;
    }
}
