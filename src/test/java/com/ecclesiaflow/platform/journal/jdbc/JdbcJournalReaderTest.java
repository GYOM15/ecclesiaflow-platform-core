package com.ecclesiaflow.platform.journal.jdbc;

import com.ecclesiaflow.platform.journal.JournalLine;
import com.ecclesiaflow.platform.journal.JournalPage;
import com.ecclesiaflow.platform.journal.JournalQuery;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static com.ecclesiaflow.platform.journal.JournalTestData.SUBJECT;
import static com.ecclesiaflow.platform.journal.JournalTestData.TARGET;
import static com.ecclesiaflow.platform.journal.JournalTestData.chain;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JdbcJournalReaderTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private JdbcJournalReader reader() {
        return new JdbcJournalReader(jdbcTemplate, new ObjectMapper());
    }

    @Test
    @DisplayName("the newest page binds only the position and the limit, plus one to tell whether a page follows")
    @SuppressWarnings("unchecked")
    void newestPage() {
        List<JournalLine> newestFirst = chain(3).reversed();
        when(jdbcTemplate.query(eq(JdbcJournalReader.SELECT_SQL + " ORDER BY seq DESC LIMIT ?"),
                any(RowMapper.class), eq(Long.MAX_VALUE), eq(4))).thenReturn(newestFirst);

        JournalPage page = reader().list(JournalQuery.newest(3));

        assertThat(page.items()).isEqualTo(newestFirst);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("a full page hands a cursor that resumes after its last line")
    @SuppressWarnings("unchecked")
    void nextPage() {
        List<JournalLine> newestFirst = chain(3).reversed();
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), any(Object[].class)))
                .thenReturn(newestFirst);

        JournalPage page = reader().list(JournalQuery.newest(2));

        assertThat(page.items()).extracting(JournalLine::seq).containsExactly(3L, 2L);
        assertThat(new JournalQuery(null, null, null, null, null, null, page.nextCursor(), 2).beforeSeq())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("every filter is a bound parameter, in the order of the statement")
    @SuppressWarnings("unchecked")
    void filters() {
        Instant from = Instant.parse("2026-11-01T00:00:00Z");
        Instant to = Instant.parse("2026-11-20T00:00:00Z");
        JournalQuery query = new JournalQuery(from, to, SUBJECT, "ACCOUNT", TARGET, "ACCOUNT_VIEWED", null, 50);
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        reader().list(query);

        verify(jdbcTemplate).query(eq(JdbcJournalReader.SELECT_SQL
                        + " AND occurred_at >= ? AND occurred_at < ? AND actor_subject = ? AND target_kind = ?"
                        + " AND target_ref = ? AND gesture = ? ORDER BY seq DESC LIMIT ?"),
                any(RowMapper.class), eq(Long.MAX_VALUE), eq(Timestamp.from(from)), eq(Timestamp.from(to)),
                eq(SUBJECT), eq("ACCOUNT"), eq(TARGET), eq("ACCOUNT_VIEWED"), eq(51));
    }
}
