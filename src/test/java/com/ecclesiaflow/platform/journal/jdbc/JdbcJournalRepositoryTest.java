package com.ecclesiaflow.platform.journal.jdbc;

import com.ecclesiaflow.platform.journal.JournalHead;
import com.ecclesiaflow.platform.journal.JournalLine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;

import static com.ecclesiaflow.platform.journal.JournalTestData.chain;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JdbcJournalRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private JdbcJournalRepository repository() {
        return new JdbcJournalRepository(jdbcTemplate, new ObjectMapper());
    }

    @Test
    @DisplayName("reads the head through the owner's function, with its hashes as hex")
    @SuppressWarnings("unchecked")
    void head() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("last_seq")).thenReturn(9L);
        when(row.getString("last_hash")).thenReturn("99".repeat(32));
        when(row.getLong("start_seq")).thenReturn(4L);
        when(row.getString("start_hash")).thenReturn("44".repeat(32));
        when(jdbcTemplate.query(eq(JdbcJournalRepository.HEAD_SQL), any(RowMapper.class)))
                .thenAnswer(call -> List.of(((RowMapper<JournalHead>) call.getArgument(1)).mapRow(row, 0)));

        assertThat(repository().head()).isEqualTo(new JournalHead(9, "99".repeat(32), 4, "44".repeat(32)));
    }

    @Test
    @DisplayName("a journal without exactly one head is refused, never read as empty")
    @SuppressWarnings("unchecked")
    void headMissing() {
        when(jdbcTemplate.query(eq(JdbcJournalRepository.HEAD_SQL), any(RowMapper.class))).thenReturn(List.of());

        assertThatThrownBy(() -> repository().head())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("0 heads");
    }

    @Test
    @DisplayName("lines are read in position order, between the bounds, up to the limit")
    @SuppressWarnings("unchecked")
    void lines() {
        List<JournalLine> lines = chain(2);
        when(jdbcTemplate.query(eq(JdbcJournalRepository.LINES_SQL), any(RowMapper.class), eq(3L), eq(9L),
                eq(500))).thenReturn(lines);

        assertThat(repository().lines(3, 9, 500)).isEqualTo(lines);
        assertThat(JdbcJournalRepository.LINES_SQL).contains("WHERE seq > ? AND seq <= ? ORDER BY seq LIMIT ?");
    }

    @Test
    @DisplayName("the hash at a position, when a line holds it")
    void rowHashAt() {
        when(jdbcTemplate.queryForList(JdbcJournalRepository.ROW_HASH_SQL, String.class, 5L))
                .thenReturn(List.of("55".repeat(32)));
        when(jdbcTemplate.queryForList(JdbcJournalRepository.ROW_HASH_SQL, String.class, 6L)).thenReturn(List.of());

        assertThat(repository().rowHashAt(5)).contains("55".repeat(32));
        assertThat(repository().rowHashAt(6)).isEmpty();
    }

    @Test
    @DisplayName("the purge is the owner's function, which takes no term from its caller")
    void purge() {
        when(jdbcTemplate.queryForObject(JdbcJournalRepository.PURGE_SQL, Long.class)).thenReturn(3L, (Long) null);

        assertThat(repository().purgeExpired()).isEqualTo(3);
        assertThat(repository().purgeExpired()).isZero();
        assertThat(JdbcJournalRepository.PURGE_SQL).isEqualTo("SELECT platform_journal.purge_expired()");
    }
}
