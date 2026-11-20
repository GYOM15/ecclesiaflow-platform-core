package com.ecclesiaflow.platform.journal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.ecclesiaflow.platform.journal.JournalTestData.TARGET;
import static com.ecclesiaflow.platform.journal.JournalTestData.TRACE;
import static com.ecclesiaflow.platform.journal.JournalTestData.actor;
import static com.ecclesiaflow.platform.journal.JournalTestData.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JournalEntryTest {

    @Nested
    @DisplayName("built from its factories")
    class Factories {

        @Test
        @DisplayName("a done gesture carries no refusal; a refused one its code")
        void doneAndRefused() {
            JournalEntry done = entry();
            JournalEntry refused = done.refused("SELF_TARGET");

            assertThat(done.outcome()).isEqualTo(JournalOutcome.DONE);
            assertThat(done.refusalCode()).isNull();
            assertThat(refused.outcome()).isEqualTo(JournalOutcome.REFUSED);
            assertThat(refused.refusalCode()).isEqualTo("SELF_TARGET");
            assertThat(refused.gesture()).isEqualTo(done.gesture());
        }

        @Test
        @DisplayName("case, reason and details are added without losing what was set")
        void withers() {
            JournalEntry entry = entry().withCase("rpt:5b0e").withReason("ABUSE")
                    .withDetail("to", "SUSPENDED").withDetail("from", "ACTIVE");

            assertThat(entry.caseRef()).isEqualTo("rpt:5b0e");
            assertThat(entry.reasonCode()).isEqualTo("ABUSE");
            assertThat(entry.details()).containsExactly(Map.entry("from", "ACTIVE"), Map.entry("to", "SUSPENDED"));
            assertThat(entry.targetRef()).isEqualTo(TARGET);
            assertThat(entry.requestId()).isEqualTo(TRACE);
        }

        @Test
        @DisplayName("details are copied and closed: the caller's map changes nothing afterwards")
        void detailsCopied() {
            Map<String, String> details = new HashMap<>(Map.of("channel", "KNOWN_PHONE"));
            JournalEntry entry = new JournalEntry(actor(), null, "PHONE_REVEALED", JournalOutcome.DONE, null,
                    "ACCOUNT", TARGET, null, null, details);

            details.put("channel", "CHURCH_LEADER");

            assertThat(entry.details()).containsEntry("channel", "KNOWN_PHONE");
            assertThatThrownBy(() -> entry.details().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
            assertThat(new JournalEntry(actor(), null, "JOURNAL_READ", JournalOutcome.DONE, null, "JOURNAL", null,
                    null, null, null).details()).isEmpty();
        }
    }

    @Nested
    @DisplayName("refused at the door")
    class Refused {

        @Test
        @DisplayName("an address, a phone number or free text is refused, and never echoed")
        void personalData() {
            for (String value : List.of("ann@example.org", "+15145551234", "5145551234", "Ann Martin", "a/b")) {
                assertThatThrownBy(() -> JournalEntry.done(actor(), null, "ACCOUNT_SEARCHED", "ACCOUNT", value))
                        .isInstanceOf(InvalidJournalEntryException.class)
                        .hasMessageNotContaining(value)
                        .extracting(error -> ((InvalidJournalEntryException) error).field()).isEqualTo("targetRef");
                assertThatThrownBy(() -> entry().withDetail("channel", value))
                        .isInstanceOf(InvalidJournalEntryException.class)
                        .hasMessageNotContaining(value);
            }
        }

        @Test
        @DisplayName("codes are UPPER_SNAKE: a gesture, a kind, a refusal or a reason in prose is refused")
        void codes() {
            assertThatThrownBy(() -> JournalEntry.done(actor(), null, "viewed the account", "ACCOUNT", TARGET))
                    .isInstanceOf(InvalidJournalEntryException.class);
            assertThatThrownBy(() -> JournalEntry.done(actor(), null, "ACCOUNT_VIEWED", "account", TARGET))
                    .isInstanceOf(InvalidJournalEntryException.class);
            assertThatThrownBy(() -> entry().refused("not allowed"))
                    .isInstanceOf(InvalidJournalEntryException.class);
            assertThatThrownBy(() -> entry().withReason("he was rude"))
                    .isInstanceOf(InvalidJournalEntryException.class);
        }

        @Test
        @DisplayName("a refused gesture needs its code, and only a refused one carries a code")
        void refusalCode() {
            assertThatThrownBy(() -> entry().refused(null))
                    .isInstanceOf(InvalidJournalEntryException.class)
                    .hasMessageContaining("refusalCode");
            assertThatThrownBy(() -> new JournalEntry(actor(), null, "ACCOUNT_VIEWED", JournalOutcome.DONE,
                    "SELF_TARGET", "ACCOUNT", TARGET, null, null, Map.of()))
                    .isInstanceOf(InvalidJournalEntryException.class)
                    .hasMessageContaining("Only a refused gesture");
        }

        @Test
        @DisplayName("the actor, the outcome, the gesture and the target kind are required")
        void required() {
            assertThatThrownBy(() -> new JournalEntry(null, null, "ACCOUNT_VIEWED", JournalOutcome.DONE, null,
                    "ACCOUNT", null, null, null, Map.of())).hasMessageContaining("actor");
            assertThatThrownBy(() -> new JournalEntry(actor(), null, "ACCOUNT_VIEWED", null, null, "ACCOUNT", null,
                    null, null, Map.of())).hasMessageContaining("outcome");
            assertThatThrownBy(() -> JournalEntry.done(actor(), null, null, "ACCOUNT", null))
                    .hasMessageContaining("gesture");
            assertThatThrownBy(() -> JournalEntry.done(actor(), null, "ACCOUNT_VIEWED", null, null))
                    .hasMessageContaining("targetKind");
        }

        @Test
        @DisplayName("values beyond their column's length are refused")
        void lengths() {
            assertThatThrownBy(() -> JournalEntry.done(actor(), null, "ACCOUNT_VIEWED", "A".repeat(33), null))
                    .isInstanceOf(InvalidJournalEntryException.class)
                    .hasMessageContaining("exceeds 32");
            assertThatThrownBy(() -> JournalEntry.done(actor(), "t".repeat(65), "ACCOUNT_VIEWED", "ACCOUNT", null))
                    .hasMessageContaining("requestId");
            assertThatThrownBy(() -> entry().withCase("c".repeat(129))).hasMessageContaining("caseRef");
        }

        @Test
        @DisplayName("details have lower-case ASCII keys, at most sixteen")
        void details() {
            assertThatThrownBy(() -> entry().withDetail("Channel", "KNOWN_PHONE"))
                    .isInstanceOf(InvalidJournalEntryException.class)
                    .hasMessageContaining("details");
            assertThatThrownBy(() -> entry().withDetail("canal-é", "KNOWN_PHONE"))
                    .isInstanceOf(InvalidJournalEntryException.class);
            Map<String, String> many = new HashMap<>();
            for (int i = 0; i < 17; i++) {
                many.put("k" + i, "v");
            }
            assertThatThrownBy(() -> new JournalEntry(actor(), null, "ACCOUNT_VIEWED", JournalOutcome.DONE, null,
                    "ACCOUNT", null, null, null, many))
                    .isInstanceOf(InvalidJournalEntryException.class)
                    .hasMessageContaining("at most 16");
        }
    }
}
