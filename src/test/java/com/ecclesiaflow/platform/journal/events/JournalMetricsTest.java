package com.ecclesiaflow.platform.journal.events;

import com.ecclesiaflow.platform.journal.JournalOutcome;
import com.ecclesiaflow.platform.journal.JournalVerdict;
import com.ecclesiaflow.platform.journal.JournalVerification;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class JournalMetricsTest {

    private static final Instant VERIFIED_AT = Instant.parse("2026-11-21T03:00:00Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private static JournalEvents.ChainVerified verified(JournalVerdict verdict) {
        return new JournalEvents.ChainVerified("auth", new JournalVerification(VERIFIED_AT, Duration.ZERO, verdict));
    }

    private double breaks() {
        return registry.get(JournalMetrics.BREAKS).tag("module", "auth").counter().count();
    }

    @Test
    @DisplayName("the break counter and the verification gauge exist at 0 before the first walk")
    void registeredAtZero() {
        new JournalMetrics(registry, "auth");

        assertThat(breaks()).isZero();
        assertThat(registry.get(JournalMetrics.VERIFIED).tag("module", "auth").gauge().value()).isZero();
    }

    @Test
    @DisplayName("each committed line is counted by gesture and outcome")
    void rows() {
        JournalMetrics metrics = new JournalMetrics(registry, "auth");

        metrics.onAppended(new JournalEvents.LineAppended("TWO_FACTOR_RESET_OPENED", JournalOutcome.DONE));
        metrics.onAppended(new JournalEvents.LineAppended("TWO_FACTOR_RESET_OPENED", JournalOutcome.DONE));
        metrics.onAppended(new JournalEvents.LineAppended("TWO_FACTOR_RESET_OPENED", JournalOutcome.REFUSED));

        assertThat(registry.get(JournalMetrics.ROWS).tags("module", "auth", "gesture", "TWO_FACTOR_RESET_OPENED",
                "outcome", "DONE").counter().count()).isEqualTo(2);
        assertThat(registry.get(JournalMetrics.ROWS).tags("outcome", "REFUSED").counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a walk sets the gauge to its end; a broken one is counted too")
    void verifications() {
        JournalMetrics metrics = new JournalMetrics(registry, "auth");

        metrics.onVerified(verified(JournalVerdict.intact(3)));
        assertThat(breaks()).isZero();
        metrics.onVerified(verified(JournalVerdict.broken(JournalVerdict.Status.LINE_REWRITTEN, 3, 2)));

        assertThat(breaks()).isEqualTo(1);
        assertThat(registry.get(JournalMetrics.VERIFIED).gauge().value()).isEqualTo(VERIFIED_AT.getEpochSecond());
    }

    @Test
    @DisplayName("without a registry nothing is counted and nothing fails")
    void withoutRegistry() {
        JournalMetrics metrics = new JournalMetrics(null, "auth");

        assertThatCode(() -> {
            metrics.onAppended(new JournalEvents.LineAppended("JOURNAL_READ", JournalOutcome.DONE));
            metrics.onVerified(verified(JournalVerdict.broken(JournalVerdict.Status.TAIL_REMOVED, 1, 2)));
        }).doesNotThrowAnyException();
    }
}
