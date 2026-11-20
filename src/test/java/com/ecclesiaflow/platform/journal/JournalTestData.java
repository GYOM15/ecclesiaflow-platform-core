package com.ecclesiaflow.platform.journal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Lines chained the way the journal's insert trigger chains them, computed apart from the code under test. */
public final class JournalTestData {

    public static final String SUBJECT = "3f2c1d4e-1111-4222-8333-944455556666";
    public static final String TARGET = "8c1d2e3f-2222-4333-8444-a55566667777";
    public static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    public static final Instant T0 = Instant.parse("2026-11-20T10:00:00.123456Z");

    private JournalTestData() {
    }

    public static JournalActor actor() {
        return JournalActor.of(SUBJECT, List.of("SUPPORT"), "a1b2c3d4-sid");
    }

    public static JournalEntry entry() {
        return JournalEntry.done(actor(), TRACE, "ACCOUNT_VIEWED", "ACCOUNT", TARGET);
    }

    /** {@code count} lines after the start point, each linked to the one before. */
    public static List<JournalLine> chain(long startSeq, String startHash, int count) {
        List<JournalLine> lines = new ArrayList<>();
        String previous = startHash;
        for (long seq = startSeq + 1; seq <= startSeq + count; seq++) {
            JournalLine line = line(seq, previous);
            lines.add(line);
            previous = line.rowHashHex();
        }
        return lines;
    }

    public static List<JournalLine> chain(int count) {
        return chain(0, null, count);
    }

    /** A line at this position linked to {@code previousHash}, its hash computed from its content. */
    public static JournalLine line(long seq, String previousHash) {
        JournalLine unhashed = new JournalLine(seq, T0.plusSeconds(seq), SUBJECT, List.of("SUPER_ADMIN", "SUPPORT"),
                "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", TRACE, "ACCOUNT_VIEWED", "DONE",
                null, "ACCOUNT", TARGET, null, null, Map.of("channel", "KNOWN_PHONE"), previousHash, null);
        return rehashed(unhashed);
    }

    /** The same line with the hash its content and link give, as a careful forger would leave it. */
    public static JournalLine rehashed(JournalLine line) {
        return withRowHash(line, hashOf(line));
    }

    public static JournalLine withRowHash(JournalLine line, String rowHash) {
        return new JournalLine(line.seq(), line.occurredAt(), line.actorSubject(), line.actorRoles(),
                line.actorSession(), line.requestId(), line.gesture(), line.outcome(), line.refusalCode(),
                line.targetKind(), line.targetRef(), line.caseRef(), line.reasonCode(), line.details(),
                line.prevHashHex(), rowHash);
    }

    public static JournalLine withTargetRef(JournalLine line, String targetRef) {
        return new JournalLine(line.seq(), line.occurredAt(), line.actorSubject(), line.actorRoles(),
                line.actorSession(), line.requestId(), line.gesture(), line.outcome(), line.refusalCode(),
                line.targetKind(), targetRef, line.caseRef(), line.reasonCode(), line.details(), line.prevHashHex(),
                line.rowHashHex());
    }

    public static JournalLine withPrevHash(JournalLine line, String prevHash) {
        return new JournalLine(line.seq(), line.occurredAt(), line.actorSubject(), line.actorRoles(),
                line.actorSession(), line.requestId(), line.gesture(), line.outcome(), line.refusalCode(),
                line.targetKind(), line.targetRef(), line.caseRef(), line.reasonCode(), line.details(), prevHash,
                line.rowHashHex());
    }

    public static JournalHead headOf(List<JournalLine> lines, long startSeq, String startHash) {
        if (lines.isEmpty()) {
            return new JournalHead(startSeq, startHash, startSeq, startHash);
        }
        JournalLine last = lines.getLast();
        return new JournalHead(last.seq(), last.rowHashHex(), startSeq, startHash);
    }

    static String hashOf(JournalLine line) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(line.prevHashHex() == null ? new byte[]{0} : HexFormat.of().parseHex(line.prevHashHex()));
            digest.update(JournalCanonicalForm.render(line).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
