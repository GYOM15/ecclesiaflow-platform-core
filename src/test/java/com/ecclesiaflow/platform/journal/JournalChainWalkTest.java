package com.ecclesiaflow.platform.journal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.ecclesiaflow.platform.journal.JournalTestData.chain;
import static com.ecclesiaflow.platform.journal.JournalTestData.headOf;
import static com.ecclesiaflow.platform.journal.JournalTestData.rehashed;
import static com.ecclesiaflow.platform.journal.JournalTestData.withPrevHash;
import static com.ecclesiaflow.platform.journal.JournalTestData.withRowHash;
import static com.ecclesiaflow.platform.journal.JournalTestData.withTargetRef;
import static org.assertj.core.api.Assertions.assertThat;

class JournalChainWalkTest {

    private static JournalVerdict walk(JournalHead head, List<JournalLine> lines) {
        JournalChainWalk walk = new JournalChainWalk(head);
        lines.forEach(walk::accept);
        return walk.verdict();
    }

    @Nested
    @DisplayName("an honest journal")
    class Honest {

        @Test
        @DisplayName("an empty journal is intact")
        void empty() {
            assertThat(walk(JournalHead.empty(), List.of())).isEqualTo(JournalVerdict.intact(0));
        }

        @Test
        @DisplayName("a chain that recomputes and ends on its head is intact")
        void intact() {
            List<JournalLine> lines = chain(5);

            assertThat(walk(headOf(lines, 0, null), lines)).isEqualTo(JournalVerdict.intact(5));
        }

        @Test
        @DisplayName("after a purge the chain verifies from the start point it left")
        void afterPurge() {
            List<JournalLine> all = chain(6);
            JournalLine lastPurged = all.get(2);
            List<JournalLine> left = all.subList(3, 6);

            assertThat(walk(headOf(all, lastPurged.seq(), lastPurged.rowHashHex()), left))
                    .isEqualTo(JournalVerdict.intact(3));
        }

        @Test
        @DisplayName("a purge that removed every line leaves a journal whose start is its end")
        void purgedEntirely() {
            List<JournalLine> all = chain(3);
            String last = all.getLast().rowHashHex();

            assertThat(walk(new JournalHead(3, last, 3, last), List.of())).isEqualTo(JournalVerdict.intact(0));
        }

        @Test
        @DisplayName("hashes are compared whatever their case")
        void caseInsensitive() {
            List<JournalLine> lines = new ArrayList<>(chain(2));
            JournalLine second = lines.get(1);
            lines.set(1, withRowHash(withPrevHash(second, second.prevHashHex().toUpperCase()),
                    second.rowHashHex().toUpperCase()));

            assertThat(walk(headOf(chain(2), 0, null), lines).intact()).isTrue();
        }
    }

    @Nested
    @DisplayName("a tampered journal")
    class Tampered {

        @Test
        @DisplayName("a line edited in place is found by its hash")
        void rewrittenLine() {
            List<JournalLine> lines = new ArrayList<>(chain(4));
            JournalHead head = headOf(lines, 0, null);
            lines.set(1, withTargetRef(lines.get(1), "ffffffff-2222-4333-8444-a55566667777"));

            assertThat(walk(head, lines)).isEqualTo(
                    JournalVerdict.broken(JournalVerdict.Status.LINE_REWRITTEN, 4, 2));
        }

        @Test
        @DisplayName("a line edited and re-hashed breaks the link of the next one")
        void rehashedLine() {
            List<JournalLine> lines = new ArrayList<>(chain(4));
            JournalHead head = headOf(lines, 0, null);
            lines.set(1, rehashed(withTargetRef(lines.get(1), "ffffffff-2222-4333-8444-a55566667777")));

            assertThat(walk(head, lines)).isEqualTo(JournalVerdict.broken(JournalVerdict.Status.LINK_BROKEN, 4, 3));
        }

        @Test
        @DisplayName("a line removed from the middle leaves a gap")
        void removedMiddle() {
            List<JournalLine> lines = new ArrayList<>(chain(4));
            JournalHead head = headOf(lines, 0, null);
            lines.remove(1);

            assertThat(walk(head, lines)).isEqualTo(JournalVerdict.broken(JournalVerdict.Status.LINK_BROKEN, 3, 2));
        }

        @Test
        @DisplayName("lines removed from the end are found by the head")
        void removedTail() {
            List<JournalLine> lines = chain(5);

            assertThat(walk(headOf(lines, 0, null), lines.subList(0, 3)))
                    .isEqualTo(JournalVerdict.broken(JournalVerdict.Status.TAIL_REMOVED, 3, 4));
        }

        @Test
        @DisplayName("a head moved back behind the lines is found")
        void headBehind() {
            List<JournalLine> lines = chain(5);

            assertThat(walk(headOf(lines.subList(0, 3), 0, null), lines))
                    .isEqualTo(JournalVerdict.broken(JournalVerdict.Status.HEAD_MISMATCH, 5, 4));
        }

        @Test
        @DisplayName("a last line replaced under the head's position is found by the head's hash")
        void lastLineReplaced() {
            List<JournalLine> lines = new ArrayList<>(chain(3));
            JournalHead head = headOf(lines, 0, null);
            lines.set(2, rehashed(withTargetRef(lines.get(2), "ffffffff-2222-4333-8444-a55566667777")));

            assertThat(walk(head, lines)).isEqualTo(JournalVerdict.broken(JournalVerdict.Status.HEAD_MISMATCH, 3, 3));
        }

        @Test
        @DisplayName("a first line that does not start from the start point breaks the link")
        void wrongStart() {
            List<JournalLine> lines = chain(4);
            JournalHead forgedStart = new JournalHead(4, lines.getLast().rowHashHex(), 1, "00".repeat(32));

            assertThat(walk(forgedStart, lines.subList(1, 4)))
                    .isEqualTo(JournalVerdict.broken(JournalVerdict.Status.LINK_BROKEN, 3, 2));
        }

        @Test
        @DisplayName("a missing or malformed hash is a rewritten line, never a crash")
        void malformedHashes() {
            List<JournalLine> lines = new ArrayList<>(chain(2));
            JournalHead head = headOf(lines, 0, null);
            lines.set(0, withRowHash(lines.get(0), null));

            assertThat(walk(head, lines).status()).isEqualTo(JournalVerdict.Status.LINE_REWRITTEN);

            JournalLine malformed = withPrevHash(chain(2).get(1), "not-hex");
            JournalHead startOnIt = new JournalHead(2, chain(2).get(1).rowHashHex(), 1, "not-hex");
            assertThat(walk(startOnIt, List.of(malformed)))
                    .isEqualTo(JournalVerdict.broken(JournalVerdict.Status.LINE_REWRITTEN, 1, 2));
        }

        @Test
        @DisplayName("the lines after a break are counted, the first break is kept")
        void firstBreakKept() {
            List<JournalLine> lines = new ArrayList<>(chain(5));
            JournalHead head = headOf(lines, 0, null);
            lines.set(1, withTargetRef(lines.get(1), "ffffffff-2222-4333-8444-a55566667777"));
            lines.set(3, withTargetRef(lines.get(3), "eeeeeeee-2222-4333-8444-a55566667777"));

            JournalVerdict verdict = walk(head, lines);

            assertThat(verdict.position()).isEqualTo(2);
            assertThat(verdict.linesRead()).isEqualTo(5);
        }
    }
}
