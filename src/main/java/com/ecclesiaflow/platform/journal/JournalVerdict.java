package com.ecclesiaflow.platform.journal;

/**
 * What a walk of the chain found.
 *
 * @param linesRead the lines walked, from the start point to the head
 * @param position  the first position where the chain fails, 0 when intact
 */
public record JournalVerdict(Status status, long linesRead, long position) {

    public enum Status {
        /** Every line recomputes and links to the one before, from the start point to the head. */
        INTACT,
        /** A line's hash does not match its content: the line was edited. */
        LINE_REWRITTEN,
        /** A line does not follow the one before: a line was removed, inserted or re-hashed. */
        LINK_BROKEN,
        /** The chain ends before the head: lines were removed from its end. */
        TAIL_REMOVED,
        /** The chain does not end on the head's position and hash: the head or the last line was replaced. */
        HEAD_MISMATCH
    }

    public JournalVerdict {
        if ((status == Status.INTACT) != (position == 0)) {
            throw new IllegalArgumentException("A broken chain names where it breaks, an intact one nowhere");
        }
    }

    public static JournalVerdict intact(long linesRead) {
        return new JournalVerdict(Status.INTACT, linesRead, 0);
    }

    public static JournalVerdict broken(Status status, long linesRead, long position) {
        return new JournalVerdict(status, linesRead, position);
    }

    public boolean intact() {
        return status == Status.INTACT;
    }
}
