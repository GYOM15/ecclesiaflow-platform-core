package com.ecclesiaflow.platform.journal;

import com.ecclesiaflow.platform.journal.events.JournalEvents;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Walks the module's chain against its head, and answers the anchor check. Each runs on one snapshot, so a gesture or a
 * purge committing meanwhile can neither shorten nor extend what is compared. The whole chain is walked every time:
 * at a few thousand lines a year it costs less than proving an incremental point was itself left untouched.
 */
public class JournalIntegrity {

    static final int BATCH = 500;

    private final JournalRepository repository;
    private final TransactionOperations snapshot;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final String module;
    private final AtomicReference<JournalVerification> last = new AtomicReference<>();

    /** @param snapshot a read-only, repeatable-read transaction on the journal's DataSource */
    public JournalIntegrity(JournalRepository repository, TransactionOperations snapshot,
                            ApplicationEventPublisher events, Clock clock, String module) {
        this.repository = repository;
        this.snapshot = snapshot;
        this.events = events;
        this.clock = clock;
        this.module = module;
    }

    /** Walks the chain from its start point to its head; the module runs it every night, under its scheduler lock. */
    public JournalVerification verify() {
        Instant started = clock.instant();
        JournalVerdict verdict = snapshot.execute(status -> walk());
        Instant ended = clock.instant();
        JournalVerification verification = new JournalVerification(ended, Duration.between(started, ended), verdict);
        last.set(verification);
        events.publishEvent(new JournalEvents.ChainVerified(module, verification));
        return verification;
    }

    /** The last walk this instance completed; empty until the first. */
    public Optional<JournalVerification> lastVerification() {
        return Optional.ofNullable(last.get());
    }

    public JournalHead head() {
        return repository.head();
    }

    /** What the journal holds at a position yesterday's anchor named. */
    public JournalPosition position(long seq) {
        return snapshot.execute(status -> positionOf(seq));
    }

    private JournalVerdict walk() {
        JournalHead head = repository.head();
        JournalChainWalk walk = new JournalChainWalk(head);
        long after = head.startSeq();
        List<JournalLine> batch;
        do {
            batch = repository.lines(after, head.lastSeq(), BATCH);
            batch.forEach(walk::accept);
            if (!batch.isEmpty()) {
                after = batch.getLast().seq();
            }
        } while (batch.size() == BATCH);
        return walk.verdict();
    }

    private JournalPosition positionOf(long seq) {
        JournalHead head = repository.head();
        if (seq < 1 || seq > head.lastSeq()) {
            return new JournalPosition(seq, JournalPosition.State.ABSENT, null);
        }
        if (seq < head.startSeq()) {
            return new JournalPosition(seq, JournalPosition.State.PURGED, null);
        }
        if (seq == head.startSeq()) {
            return new JournalPosition(seq, JournalPosition.State.START, head.startHashHex());
        }
        return repository.rowHashAt(seq)
                .map(hash -> new JournalPosition(seq, JournalPosition.State.LINE, hash))
                .orElseGet(() -> new JournalPosition(seq, JournalPosition.State.ABSENT, null));
    }
}
