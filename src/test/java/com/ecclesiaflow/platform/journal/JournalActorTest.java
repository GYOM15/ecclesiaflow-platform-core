package com.ecclesiaflow.platform.journal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static com.ecclesiaflow.platform.journal.JournalTestData.SUBJECT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JournalActorTest {

    @Test
    @DisplayName("the session id is kept as its SHA-256, never as the value that could replay it")
    void sessionHashed() {
        JournalActor actor = JournalActor.of(SUBJECT, List.of("SUPPORT"), "abc");

        assertThat(actor.sessionDigest())
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
                .doesNotContain("abc");
    }

    @Test
    @DisplayName("without a session the digest is absent")
    void noSession() {
        assertThat(JournalActor.of(SUBJECT, List.of(), null).sessionDigest()).isNull();
        assertThat(JournalActor.of(SUBJECT, List.of(), "  ").sessionDigest()).isNull();
    }

    @Test
    @DisplayName("roles are sorted and deduplicated, so one operator's lines always read the same")
    void rolesSorted() {
        assertThat(JournalActor.of(SUBJECT, List.of("SUPPORT", "SUPER_ADMIN", "SUPPORT"), null).roles())
                .containsExactly("SUPER_ADMIN", "SUPPORT");
        assertThat(JournalActor.of(SUBJECT, null, null).roles()).isEmpty();
    }

    @Test
    @DisplayName("a role that is not a code, a null role or a subject that is not an id is refused")
    void refused() {
        assertThatThrownBy(() -> JournalActor.of(SUBJECT, List.of("support"), null))
                .isInstanceOf(InvalidJournalEntryException.class);
        assertThatThrownBy(() -> JournalActor.of(SUBJECT, Arrays.asList("SUPPORT", null), null))
                .isInstanceOf(InvalidJournalEntryException.class);
        assertThatThrownBy(() -> JournalActor.of("ann@example.org", List.of(), null))
                .isInstanceOf(InvalidJournalEntryException.class)
                .hasMessageNotContaining("ann@example.org");
        assertThatThrownBy(() -> JournalActor.of(null, List.of(), null))
                .isInstanceOf(InvalidJournalEntryException.class);
    }

    @Test
    @DisplayName("a digest that is not lowercase SHA-256 hex is refused")
    void digestShape() {
        assertThatThrownBy(() -> new JournalActor(SUBJECT, List.of(), "raw-session-id"))
                .isInstanceOf(InvalidJournalEntryException.class)
                .hasMessageContaining("actorSession");
    }
}
