package com.ecclesiaflow.platform.journal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static com.ecclesiaflow.platform.journal.JournalTestData.SUBJECT;
import static com.ecclesiaflow.platform.journal.JournalTestData.TARGET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JournalQueryTest {

    private static JournalQuery withCursor(String cursor) {
        return new JournalQuery(null, null, null, null, null, null, cursor, JournalQuery.DEFAULT_LIMIT);
    }

    private static String encoded(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    @DisplayName("the first page starts from the newest line; a cursor resumes strictly before its position")
    void cursor() {
        assertThat(JournalQuery.newest(10).beforeSeq()).isEqualTo(Long.MAX_VALUE);
        assertThat(withCursor(JournalQuery.cursorAfter(42)).beforeSeq()).isEqualTo(42);
    }

    @Test
    @DisplayName("a cursor the journal did not issue is refused")
    void foreignCursor() {
        for (String cursor : new String[]{"not base64!", encoded("x1:42"), encoded("pj1:0"), encoded("pj1:-3"),
                encoded("pj1:4a"), encoded("pj1:" + "9".repeat(19))}) {
            assertThatThrownBy(() -> withCursor(cursor))
                    .as(cursor)
                    .isInstanceOf(InvalidJournalQueryException.class)
                    .hasMessageContaining("cursor");
        }
    }

    @Test
    @DisplayName("a page holds 1 to 200 lines")
    void limits() {
        assertThat(JournalQuery.newest(JournalQuery.MAX_LIMIT).limit()).isEqualTo(200);
        assertThatThrownBy(() -> JournalQuery.newest(0)).isInstanceOf(InvalidJournalQueryException.class);
        assertThatThrownBy(() -> JournalQuery.newest(201)).isInstanceOf(InvalidJournalQueryException.class);
    }

    @Test
    @DisplayName("a period ends after it starts")
    void period() {
        Instant from = Instant.parse("2026-11-01T00:00:00Z");

        assertThat(new JournalQuery(from, from.plusSeconds(1), null, null, null, null, null, 10).from())
                .isEqualTo(from);
        assertThat(new JournalQuery(from, null, null, null, null, null, null, 10).to()).isNull();
        assertThatThrownBy(() -> new JournalQuery(from, from, null, null, null, null, null, 10))
                .isInstanceOf(InvalidJournalQueryException.class);
    }

    @Test
    @DisplayName("filters are ids and codes; a target is named by its kind and its reference")
    void filters() {
        JournalQuery query = new JournalQuery(null, null, SUBJECT, "ACCOUNT", TARGET, "ACCOUNT_VIEWED", null, 10);

        assertThat(query.actorSubject()).isEqualTo(SUBJECT);
        assertThat(query.targetRef()).isEqualTo(TARGET);
        assertThatThrownBy(() -> new JournalQuery(null, null, null, null, TARGET, null, null, 10))
                .isInstanceOf(InvalidJournalQueryException.class)
                .hasMessageContaining("kind");
        assertThatThrownBy(() -> new JournalQuery(null, null, "ann@example.org", null, null, null, null, 10))
                .isInstanceOf(InvalidJournalQueryException.class)
                .hasMessageContaining("actorSubject")
                .hasMessageNotContaining("ann@example.org");
        assertThatThrownBy(() -> new JournalQuery(null, null, null, "account", null, null, null, 10))
                .hasMessageContaining("targetKind");
        assertThatThrownBy(() -> new JournalQuery(null, null, null, "ACCOUNT", "a b", null, null, 10))
                .hasMessageContaining("targetRef");
        assertThatThrownBy(() -> new JournalQuery(null, null, null, null, null, "viewed", null, 10))
                .hasMessageContaining("gesture");
    }
}
