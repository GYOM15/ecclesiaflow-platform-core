package com.ecclesiaflow.platform.journal.jdbc;

import com.ecclesiaflow.platform.journal.JournalLine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JournalLineRowMapperTest {

    private final JournalLineRowMapper mapper = new JournalLineRowMapper(new ObjectMapper());

    private static ResultSet row(Array roles, String details) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("seq")).thenReturn(3L);
        when(rs.getLong("occurred_micros")).thenReturn(1_795_168_800_123_456L);
        when(rs.getString("actor_subject")).thenReturn("sub-1");
        when(rs.getArray("actor_roles")).thenReturn(roles);
        when(rs.getString("actor_session")).thenReturn("ab".repeat(32));
        when(rs.getString("request_id")).thenReturn("trace-1");
        when(rs.getString("gesture")).thenReturn("ACCOUNT_VIEWED");
        when(rs.getString("outcome")).thenReturn("DONE");
        when(rs.getString("refusal_code")).thenReturn(null);
        when(rs.getString("target_kind")).thenReturn("ACCOUNT");
        when(rs.getString("target_ref")).thenReturn("acc-1");
        when(rs.getString("case_ref")).thenReturn("case-1");
        when(rs.getString("reason_code")).thenReturn("ABUSE");
        when(rs.getString("details")).thenReturn(details);
        when(rs.getString("prev_hash")).thenReturn("22".repeat(32));
        when(rs.getString("row_hash")).thenReturn("33".repeat(32));
        return rs;
    }

    @Test
    @DisplayName("reads every column as the database renders it")
    void mapsEveryColumn() throws SQLException {
        Array roles = mock(Array.class);
        when(roles.getArray()).thenReturn(new String[]{"SUPER_ADMIN", "SUPPORT"});

        JournalLine line = mapper.mapRow(row(roles, "{\"channel\": \"KNOWN_PHONE\"}"), 0);

        assertThat(line).isEqualTo(new JournalLine(3, Instant.parse("2026-11-20T10:00:00.123456Z"), "sub-1",
                List.of("SUPER_ADMIN", "SUPPORT"), "ab".repeat(32), "trace-1", "ACCOUNT_VIEWED", "DONE",
                null, "ACCOUNT", "acc-1", "case-1", "ABUSE", Map.of("channel", "KNOWN_PHONE"),
                "22".repeat(32), "33".repeat(32)));
    }

    @Test
    @DisplayName("an absent role array reads as no role")
    void noRoles() throws SQLException {
        assertThat(mapper.mapRow(row(null, "{}"), 0).actorRoles()).isEmpty();
    }

    @Test
    @DisplayName("details that are not the journal's JSON stop the read instead of being guessed")
    void unreadableDetails() throws SQLException {
        ResultSet rs = row(null, "not json");

        assertThatThrownBy(() -> mapper.mapRow(rs, 0))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("details");
    }

    @Test
    @DisplayName("microseconds before the epoch read back to the same instant")
    void beforeEpoch() {
        assertThat(JournalLineRowMapper.instantOf(-1)).isEqualTo(Instant.parse("1969-12-31T23:59:59.999999Z"));
    }
}
