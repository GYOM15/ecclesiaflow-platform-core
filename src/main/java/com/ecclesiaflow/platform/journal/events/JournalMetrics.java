package com.ecclesiaflow.platform.journal.events;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code ef_platform_journal_rows_total{module,gesture,outcome}},
 * {@code ef_platform_journal_chain_breaks_total{module}} and {@code ef_platform_journal_verified_timestamp{module}}
 * (epoch seconds of the last completed walk, 0 before it).
 * Without a registry they count nothing; nothing else changes.
 */
public class JournalMetrics {

    static final String ROWS = "ef.platform.journal.rows";
    static final String BREAKS = "ef.platform.journal.chain.breaks";
    static final String VERIFIED = "ef.platform.journal.verified.timestamp";

    private final MeterRegistry registry;
    private final String module;
    private final AtomicLong verifiedEpochSeconds = new AtomicLong();
    private final Counter breaks;

    public JournalMetrics(MeterRegistry registry, String module) {
        this.registry = registry;
        this.module = module;
        if (registry == null) {
            this.breaks = null;
            return;
        }
        // Registered at 0, so the alert on an increase sees the series before the first break.
        this.breaks = Counter.builder(BREAKS)
                .description("Walks of the journal's chain that found it broken")
                .tag("module", module)
                .register(registry);
        Gauge.builder(VERIFIED, verifiedEpochSeconds, AtomicLong::get)
                .description("Epoch seconds at which the last walk of the journal's chain ended")
                .tag("module", module)
                .register(registry);
    }

    // After commit: a gesture rolled back left no line, so it is no row.
    @TransactionalEventListener
    public void onAppended(JournalEvents.LineAppended event) {
        if (registry == null) {
            return;
        }
        Counter.builder(ROWS)
                .description("Lines written to the journal, by gesture and outcome")
                .tag("module", module)
                .tag("gesture", event.gesture())
                .tag("outcome", event.outcome().name())
                .register(registry)
                .increment();
    }

    @EventListener
    public void onVerified(JournalEvents.ChainVerified event) {
        verifiedEpochSeconds.set(event.verification().verifiedAt().getEpochSecond());
        if (breaks != null && !event.verification().verdict().intact()) {
            breaks.increment();
        }
    }
}
