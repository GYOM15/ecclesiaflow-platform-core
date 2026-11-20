package com.ecclesiaflow.platform.journal;

import com.ecclesiaflow.platform.journal.events.JournalEvents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static com.ecclesiaflow.platform.journal.JournalTestData.chain;
import static com.ecclesiaflow.platform.journal.JournalTestData.headOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JournalIntegrityTest {

    private static final Instant NOW = Instant.parse("2026-11-21T03:00:00Z");

    @Mock
    private JournalRepository repository;
    @Mock
    private ApplicationEventPublisher events;

    private int snapshots;

    /** Runs the callback as the read-only, repeatable-read transaction would, counting how often one is opened. */
    private final TransactionOperations snapshot = new TransactionOperations() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            snapshots++;
            return action.doInTransaction(null);
        }
    };

    private JournalIntegrity integrity() {
        return new JournalIntegrity(repository, snapshot, events, Clock.fixed(NOW, ZoneOffset.UTC), "members");
    }

    @Nested
    @DisplayName("verify")
    class Verify {

        @Test
        @DisplayName("walks every line from the start point to the head in batches, in one snapshot")
        void walksInBatches() {
            List<JournalLine> lines = chain(JournalIntegrity.BATCH + 3);
            JournalHead head = headOf(lines, 0, null);
            when(repository.head()).thenReturn(head);
            when(repository.lines(0, head.lastSeq(), JournalIntegrity.BATCH))
                    .thenReturn(lines.subList(0, JournalIntegrity.BATCH));
            when(repository.lines(JournalIntegrity.BATCH, head.lastSeq(), JournalIntegrity.BATCH))
                    .thenReturn(lines.subList(JournalIntegrity.BATCH, lines.size()));

            JournalVerification verification = integrity().verify();

            assertThat(verification.verdict()).isEqualTo(JournalVerdict.intact(JournalIntegrity.BATCH + 3));
            assertThat(verification.verifiedAt()).isEqualTo(NOW);
            assertThat(verification.took()).isEqualTo(Duration.ZERO);
            assertThat(snapshots).isEqualTo(1);
        }

        @Test
        @DisplayName("an empty journal is read once and found intact")
        void empty() {
            when(repository.head()).thenReturn(JournalHead.empty());
            when(repository.lines(0, 0, JournalIntegrity.BATCH)).thenReturn(List.of());

            assertThat(integrity().verify().verdict()).isEqualTo(JournalVerdict.intact(0));
        }

        @Test
        @DisplayName("publishes the verification and remembers it as the last one")
        void publishesAndRemembers() {
            List<JournalLine> lines = chain(3);
            when(repository.head()).thenReturn(headOf(lines, 0, null));
            when(repository.lines(anyLong(), anyLong(), anyInt())).thenReturn(lines);
            JournalIntegrity integrity = integrity();
            assertThat(integrity.lastVerification()).isEmpty();

            JournalVerification verification = integrity.verify();

            ArgumentCaptor<JournalEvents.ChainVerified> event =
                    ArgumentCaptor.forClass(JournalEvents.ChainVerified.class);
            verify(events).publishEvent(event.capture());
            assertThat(event.getValue()).isEqualTo(new JournalEvents.ChainVerified("members", verification));
            assertThat(integrity.lastVerification()).contains(verification);
        }

        @Test
        @DisplayName("a tail removed below the head is reported")
        void tailRemoved() {
            List<JournalLine> lines = chain(4);
            when(repository.head()).thenReturn(headOf(lines, 0, null));
            when(repository.lines(anyLong(), anyLong(), anyInt())).thenReturn(lines.subList(0, 2));

            assertThat(integrity().verify().verdict().status()).isEqualTo(JournalVerdict.Status.TAIL_REMOVED);
        }
    }

    @Nested
    @DisplayName("position")
    class Position {

        private final JournalHead head = new JournalHead(9, "99".repeat(32), 4, "44".repeat(32));

        @Test
        @DisplayName("a line's hash, the start point's, or nothing to compare once purged")
        void states() {
            when(repository.head()).thenReturn(head);
            when(repository.rowHashAt(6)).thenReturn(Optional.of("66".repeat(32)));
            JournalIntegrity integrity = integrity();

            assertThat(integrity.position(6))
                    .isEqualTo(new JournalPosition(6, JournalPosition.State.LINE, "66".repeat(32)));
            assertThat(integrity.position(4))
                    .isEqualTo(new JournalPosition(4, JournalPosition.State.START, "44".repeat(32)));
            assertThat(integrity.position(2)).isEqualTo(new JournalPosition(2, JournalPosition.State.PURGED, null));
            assertThat(snapshots).isEqualTo(3);
        }

        @Test
        @DisplayName("a position beyond the head, below 1, or whose line is gone, is absent")
        void absent() {
            when(repository.head()).thenReturn(head);
            when(repository.rowHashAt(7)).thenReturn(Optional.empty());
            JournalIntegrity integrity = integrity();

            assertThat(integrity.position(10).state()).isEqualTo(JournalPosition.State.ABSENT);
            assertThat(integrity.position(0).state()).isEqualTo(JournalPosition.State.ABSENT);
            assertThat(integrity.position(7)).isEqualTo(new JournalPosition(7, JournalPosition.State.ABSENT, null));
            verify(repository, never()).rowHashAt(10);
        }

        @Test
        @DisplayName("the head is read as stored")
        void head() {
            when(repository.head()).thenReturn(head);

            assertThat(integrity().head()).isEqualTo(head);
        }
    }
}
