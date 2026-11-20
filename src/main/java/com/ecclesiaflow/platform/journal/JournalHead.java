package com.ecclesiaflow.platform.journal;

/**
 * How far the chain went and where it starts. A chain cannot prove its own end: a removed tail leaves the head ahead
 * of the lines. After a purge the chain starts after {@code startSeq}, whose hash the first remaining line links to.
 *
 * @param lastSeq      the last position written, 0 for an empty journal
 * @param lastHashHex  that line's hash, {@code null} with position 0
 * @param startSeq     the last position purged, 0 before any purge
 * @param startHashHex that line's hash, {@code null} with position 0
 */
public record JournalHead(long lastSeq, String lastHashHex, long startSeq, String startHashHex) {

    private static final JournalHead EMPTY = new JournalHead(0, null, 0, null);

    public JournalHead {
        if (lastSeq < 0 || startSeq < 0 || startSeq > lastSeq) {
            throw new IllegalArgumentException("A journal head starts at or before its end, both from 0");
        }
        if ((lastSeq == 0) != (lastHashHex == null) || (startSeq == 0) != (startHashHex == null)) {
            throw new IllegalArgumentException("A journal head carries a position and its hash together, or neither");
        }
    }

    public static JournalHead empty() {
        return EMPTY;
    }
}
