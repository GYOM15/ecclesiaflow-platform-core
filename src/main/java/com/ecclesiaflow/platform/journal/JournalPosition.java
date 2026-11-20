package com.ecclesiaflow.platform.journal;

/**
 * The hash the journal holds at a position, for the comparison with yesterday's anchor copied off the server.
 *
 * @param hashHex the line's hash, or the start point's; {@code null} when {@code PURGED} or {@code ABSENT}
 */
public record JournalPosition(long seq, State state, String hashHex) {

    public enum State {
        /** A line holds the position. */
        LINE,
        /** The position is the start point a purge left: the hash of the last line removed. */
        START,
        /** Removed by a purge before the start point; nothing left to compare. */
        PURGED,
        /** No line holds a position the chain reached: beyond the head, or removed. */
        ABSENT
    }
}
