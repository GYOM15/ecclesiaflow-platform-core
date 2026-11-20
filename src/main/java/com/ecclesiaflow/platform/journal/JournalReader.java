package com.ecclesiaflow.platform.journal;

/** Pages of the journal for the back-office; the module journals the reading itself ({@code JOURNAL_READ}). */
public interface JournalReader {

    /** @throws InvalidJournalQueryException on a cursor this journal did not issue */
    JournalPage list(JournalQuery query);
}
