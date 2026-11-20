package com.ecclesiaflow.platform.journal.events;

import com.ecclesiaflow.platform.journal.JournalOutcome;
import com.ecclesiaflow.platform.journal.JournalVerification;

/** What the journal did, published for the listeners that log and count it; the journal itself never logs. */
public final class JournalEvents {

    private JournalEvents() {
    }

    /** A line was written in the gesture's transaction; counted only once that transaction commits. */
    public record LineAppended(String gesture, JournalOutcome outcome) {
    }

    /** A walk of the chain ended. */
    public record ChainVerified(String module, JournalVerification verification) {
    }

    /** A purge ran; {@code startSeq} is the chain's start point after it. */
    public record Purged(String module, long removed, long startSeq) {
    }
}
