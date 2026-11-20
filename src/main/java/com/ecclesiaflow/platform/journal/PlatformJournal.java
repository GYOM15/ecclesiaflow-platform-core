package com.ecclesiaflow.platform.journal;

/**
 * Writes the platform gestures of a module, reads included, in the module's tamper-evident journal. A gesture that
 * cannot be journaled does not happen: the line is written in the gesture's own transaction, so it commits with the
 * gesture or not at all.
 */
public interface PlatformJournal {

    /**
     * Appends one line; the database sets its position and its hashes.
     *
     * @return the line as written
     * @throws org.springframework.transaction.IllegalTransactionStateException outside a writable transaction that
     *                                                                          holds a connection of the journal's
     *                                                                          DataSource
     */
    JournalLine append(JournalEntry entry);
}
