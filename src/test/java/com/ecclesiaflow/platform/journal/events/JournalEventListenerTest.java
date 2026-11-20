package com.ecclesiaflow.platform.journal.events;

import com.ecclesiaflow.platform.journal.JournalVerdict;
import com.ecclesiaflow.platform.journal.JournalVerification;
import nl.altindag.log.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class JournalEventListenerTest {

    private final LogCaptor logs = LogCaptor.forClass(JournalEventListener.class);
    private final JournalEventListener listener = new JournalEventListener();

    @AfterEach
    void close() {
        logs.close();
    }

    private static JournalEvents.ChainVerified verified(JournalVerdict verdict) {
        return new JournalEvents.ChainVerified("members",
                new JournalVerification(Instant.parse("2026-11-21T03:00:00Z"), Duration.ofMillis(42), verdict));
    }

    @Test
    @DisplayName("an intact chain is an INFO line with what was walked")
    void intact() {
        listener.onVerified(verified(JournalVerdict.intact(1200)));

        assertThat(logs.getInfoLogs()).singleElement().asString()
                .isEqualTo("JOURNAL: ✅ members chain intact — 1200 lines verified in 42 ms");
    }

    @Test
    @DisplayName("a broken chain is an ERROR naming the position and the kind of break")
    void broken() {
        listener.onVerified(verified(JournalVerdict.broken(JournalVerdict.Status.TAIL_REMOVED, 30, 31)));

        assertThat(logs.getErrorLogs()).singleElement().asString()
                .isEqualTo("JOURNAL: ❌ members chain broken at position 31 — TAIL_REMOVED (30 lines read)");
    }

    @Test
    @DisplayName("a purge that removed lines is an INFO line; one that removed none stays at DEBUG")
    void purged() {
        logs.setLogLevelToDebug();

        listener.onPurged(new JournalEvents.Purged("church", 12, 12));
        listener.onPurged(new JournalEvents.Purged("church", 0, 12));

        assertThat(logs.getInfoLogs()).singleElement().asString()
                .isEqualTo("JOURNAL: 🧹 church purged 12 lines past the retention; the chain now starts after "
                        + "position 12");
        assertThat(logs.getDebugLogs()).singleElement().asString()
                .isEqualTo("JOURNAL: church nothing past the retention");
    }
}
