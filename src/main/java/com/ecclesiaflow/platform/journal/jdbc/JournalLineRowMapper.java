package com.ecclesiaflow.platform.journal.jdbc;

import com.ecclesiaflow.platform.journal.JournalLine;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Reads a line as the database renders it (epoch microseconds, hex hashes, the details' JSON), so neither a
 * connection's {@code TimeZone} nor its {@code DateStyle} can change what the verifier recomputes.
 */
final class JournalLineRowMapper implements RowMapper<JournalLine> {

    static final String COLUMNS = """
            seq, (extract(epoch FROM occurred_at) * 1000000)::bigint AS occurred_micros, actor_subject, actor_roles,
            actor_session, request_id, gesture, outcome, refusal_code, target_kind, target_ref, case_ref,
            reason_code, details::text AS details, encode(prev_hash, 'hex') AS prev_hash,
            encode(row_hash, 'hex') AS row_hash""";

    private static final TypeReference<Map<String, String>> DETAILS = new TypeReference<>() { };
    private static final long MICROS_PER_SECOND = 1_000_000L;
    private static final long NANOS_PER_MICRO = 1_000L;

    private final ObjectMapper json;

    JournalLineRowMapper(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public JournalLine mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new JournalLine(rs.getLong("seq"), instantOf(rs.getLong("occurred_micros")),
                rs.getString("actor_subject"), roles(rs.getArray("actor_roles")), rs.getString("actor_session"),
                rs.getString("request_id"), rs.getString("gesture"), rs.getString("outcome"),
                rs.getString("refusal_code"), rs.getString("target_kind"), rs.getString("target_ref"),
                rs.getString("case_ref"), rs.getString("reason_code"), details(rs.getString("details")),
                rs.getString("prev_hash"), rs.getString("row_hash"));
    }

    static Instant instantOf(long micros) {
        return Instant.ofEpochSecond(Math.floorDiv(micros, MICROS_PER_SECOND),
                Math.floorMod(micros, MICROS_PER_SECOND) * NANOS_PER_MICRO);
    }

    private static List<String> roles(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return Arrays.asList((String[]) array.getArray());
    }

    private Map<String, String> details(String text) throws SQLException {
        try {
            return json.readValue(text, DETAILS);
        } catch (JsonProcessingException unreadable) {
            throw new SQLException("A journal line's details are not the flat JSON object the journal writes",
                    unreadable);
        }
    }
}
