package com.ecclesiaflow.platform.journal;

import com.ecclesiaflow.platform.journal.events.JournalEvents;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Removes the lines past the retention (24 months, fixed in the journal's SQL, so no caller can shorten it) and leaves
 * the chain verifiable from the start point the purge records.
 */
public class JournalRetention {

    private final JournalRepository repository;
    private final ApplicationEventPublisher events;
    private final String module;

    public JournalRetention(JournalRepository repository, ApplicationEventPublisher events, String module) {
        this.repository = repository;
        this.events = events;
        this.module = module;
    }

    /** The module runs it every night, under its scheduler lock; returns how many lines left. */
    public long purgeExpired() {
        long removed = repository.purgeExpired();
        events.publishEvent(new JournalEvents.Purged(module, removed, repository.head().startSeq()));
        return removed;
    }
}
