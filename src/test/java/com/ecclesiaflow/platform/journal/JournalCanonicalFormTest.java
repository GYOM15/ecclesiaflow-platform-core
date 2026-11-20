package com.ecclesiaflow.platform.journal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JournalCanonicalFormTest {

    private static JournalLine line(String targetRef, List<String> roles, Map<String, String> details) {
        return new JournalLine(7, Instant.parse("2026-11-20T10:00:00.123456Z"), "sub-1", roles, null, "trace-1",
                "ACCOUNT_SUSPENDED", "REFUSED", "SELF_TARGET", "ACCOUNT", targetRef, "case-9", "ABUSE", details,
                "ab", "cd");
    }

    @Test
    @DisplayName("renders every column but the hashes, in the frozen order the database renders them")
    void frozenOrder() {
        String canonical = JournalCanonicalForm.render(line("acc-1", List.of("SUPER_ADMIN", "SUPPORT"),
                Map.of("to", "SUSPENDED", "from", "ACTIVE")));

        assertThat(canonical).isEqualTo("7|1795168800123456|sub-1|SUPER_ADMIN\\|SUPPORT||trace-1|ACCOUNT_SUSPENDED"
                + "|REFUSED|SELF_TARGET|ACCOUNT|acc-1|case-9|ABUSE|from\\|ACTIVE\\|to\\|SUSPENDED");
    }

    @Test
    @DisplayName("an absent value and an empty list render as empty fields")
    void absentValues() {
        JournalLine bare = new JournalLine(1, Instant.EPOCH, "sub-1", null, null, null, "JOURNAL_READ", "DONE", null,
                "JOURNAL", null, null, null, null, null, "ef");

        assertThat(JournalCanonicalForm.render(bare)).isEqualTo("1|0|sub-1||||JOURNAL_READ|DONE||JOURNAL||||");
    }

    @Test
    @DisplayName("backslash then separator are escaped, so no value slides into the next field")
    void escaping() {
        assertThat(JournalCanonicalForm.escape("a|b\\c")).isEqualTo("a\\|b\\\\c");
        assertThat(JournalCanonicalForm.escape(null)).isEmpty();

        assertThat(JournalCanonicalForm.render(line("a|b", List.of(), Map.of())))
                .isNotEqualTo(JournalCanonicalForm.render(line("a", List.of(), Map.of())));
    }

    @Test
    @DisplayName("details are sorted by key whatever the map's order; roles keep the order stored")
    void ordering() {
        Map<String, String> unordered = new LinkedHashMap<>();
        unordered.put("zeta", "Z");
        unordered.put("alpha", "A");

        assertThat(JournalCanonicalForm.render(line("acc-1", Arrays.asList("SUPPORT", "SUPER_ADMIN"), unordered)))
                .contains("|SUPPORT\\|SUPER_ADMIN|")
                .endsWith("|alpha\\|A\\|zeta\\|Z");
    }

    @Test
    @DisplayName("an instant before the epoch renders as the database's negative microseconds")
    void beforeEpoch() {
        assertThat(JournalCanonicalForm.micros(Instant.parse("1969-12-31T23:59:59.999999Z"))).isEqualTo("-1");
    }
}
