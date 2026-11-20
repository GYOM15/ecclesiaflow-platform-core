package com.ecclesiaflow.platform.journal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Recomputes the chain line by line from the head's start point, holding only the previous hash, so the whole journal
 * is verified without being in memory. Pure: no database, Spring or clock.
 */
public final class JournalChainWalk {

    /** What the database chains the first line onto. */
    private static final byte[] FIRST_LINK = {0x00};

    private final JournalHead head;
    private long expectedSeq;
    private String previousHash;
    private long linesRead;
    private JournalVerdict.Status broken;
    private long brokenAt;

    public JournalChainWalk(JournalHead head) {
        this.head = head;
        this.expectedSeq = head.startSeq() + 1;
        this.previousHash = head.startHashHex();
    }

    /** Feeds the next line, in position order; after the first break the rest is only counted. */
    public void accept(JournalLine line) {
        linesRead++;
        if (broken != null) {
            return;
        }
        // A gap is evidence even where the hashes were recomputed around it: only the purge removes lines.
        if (line.seq() != expectedSeq) {
            breakAt(JournalVerdict.Status.LINK_BROKEN, expectedSeq);
        } else if (!sameHash(previousHash, line.prevHashHex())) {
            breakAt(JournalVerdict.Status.LINK_BROKEN, line.seq());
        } else if (!recomputes(line)) {
            breakAt(JournalVerdict.Status.LINE_REWRITTEN, line.seq());
        } else {
            previousHash = line.rowHashHex();
            expectedSeq++;
        }
    }

    /** The head last: intact links prove nothing about lines no longer there. */
    public JournalVerdict verdict() {
        if (broken != null) {
            return JournalVerdict.broken(broken, linesRead, brokenAt);
        }
        long reached = expectedSeq - 1;
        if (reached < head.lastSeq()) {
            return JournalVerdict.broken(JournalVerdict.Status.TAIL_REMOVED, linesRead, expectedSeq);
        }
        if (reached > head.lastSeq()) {
            return JournalVerdict.broken(JournalVerdict.Status.HEAD_MISMATCH, linesRead, head.lastSeq() + 1);
        }
        if (!sameHash(previousHash, head.lastHashHex())) {
            return JournalVerdict.broken(JournalVerdict.Status.HEAD_MISMATCH, linesRead, head.lastSeq());
        }
        return JournalVerdict.intact(linesRead);
    }

    private void breakAt(JournalVerdict.Status status, long position) {
        broken = status;
        brokenAt = position;
    }

    private static boolean recomputes(JournalLine line) {
        String digest = digestOf(line);
        return digest != null && line.rowHashHex() != null && digest.equalsIgnoreCase(line.rowHashHex());
    }

    // A malformed hash is a rewritten line, never a crash that leaves the journal unverified.
    private static String digestOf(JournalLine line) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(line.prevHashHex() == null ? FIRST_LINK : HexFormat.of().parseHex(line.prevHashHex()));
            digest.update(JournalCanonicalForm.render(line).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (IllegalArgumentException malformed) {
            return null;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", impossible);
        }
    }

    // Case-insensitive: the database encodes lowercase, a hand-written fixture may not.
    private static boolean sameHash(String left, String right) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        return left.equalsIgnoreCase(right);
    }
}
