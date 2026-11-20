package com.ecclesiaflow.platform.webhooks.processing;

import com.ecclesiaflow.platform.webhooks.InboundNotificationHandler;
import com.ecclesiaflow.platform.webhooks.RecordedNotification;
import com.ecclesiaflow.platform.webhooks.WebhookTestData;
import com.ecclesiaflow.platform.webhooks.events.WebhookInboxEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InboxProcessorTest {

    // Sub-millisecond on purpose: the lease is compared to the millisecond the database keeps.
    private static final Instant CLOCK = Instant.parse("2026-11-20T10:00:00.000400Z");
    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final Instant LEASE_UNTIL = NOW.plus(LEASE);
    private static final int BATCH = 3;

    @Mock
    private InboxRepository repository;
    @Mock
    private InboundNotificationHandler handler;
    @Mock
    private ApplicationEventPublisher events;

    private InboxProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new InboxProcessor(repository, handler,
                new InboxRetryPolicy(10, Duration.ofSeconds(30), 2.0, Duration.ofHours(1)),
                new InboxProcessor.Settings(BATCH, LEASE), events, Clock.fixed(CLOCK, ZoneOffset.UTC));
    }

    private static RecordedNotification recorded(long id, int attempts) {
        return new RecordedNotification(id, WebhookTestData.notification("evt_" + id), NOW, attempts);
    }

    @Test
    @DisplayName("claims nothing and reports an empty batch when nothing is due")
    void nothingDue() {
        when(repository.claimDue(NOW, LEASE_UNTIL, BATCH)).thenReturn(List.of());

        assertThat(processor.processDue()).isEqualTo(ProcessingBatchResult.EMPTY);

        verifyNoInteractions(handler, events);
    }

    @Nested
    @DisplayName("a notification handled")
    class Handled {

        @Test
        @DisplayName("is closed with the handler's label, fenced on the lease, then reported")
        void closedWithItsLabel() {
            RecordedNotification one = recorded(1, 2);
            when(repository.claimDue(NOW, LEASE_UNTIL, BATCH)).thenReturn(List.of(one));
            when(handler.handle(one)).thenReturn("APPLIED");

            ProcessingBatchResult result = processor.processDue();

            assertThat(result).isEqualTo(new ProcessingBatchResult(1, 1, 0, 0, false));
            InOrder order = inOrder(repository, events);
            order.verify(repository).markDone(1, LEASE_UNTIL, "APPLIED", CLOCK);
            order.verify(events).publishEvent(new WebhookInboxEvents.NotificationHandled(1, "acme", "payment.paid",
                    "APPLIED", 3));
        }

        @Test
        @DisplayName("gets a default label when the handler gives none, and a long one cut to its bound")
        void labels() {
            RecordedNotification blank = recorded(1, 0);
            RecordedNotification none = recorded(2, 0);
            RecordedNotification verbose = recorded(3, 0);
            when(repository.claimDue(NOW, LEASE_UNTIL, BATCH)).thenReturn(List.of(blank, none, verbose));
            when(handler.handle(blank)).thenReturn(" ");
            when(handler.handle(none)).thenReturn(null);
            when(handler.handle(verbose)).thenReturn("A".repeat(InboxProcessor.MAX_OUTCOME_LENGTH + 1));

            ProcessingBatchResult result = processor.processDue();

            assertThat(result.batchWasFull()).isTrue();
            verify(repository).markDone(1, LEASE_UNTIL, InboxProcessor.DEFAULT_OUTCOME, CLOCK);
            verify(repository).markDone(2, LEASE_UNTIL, InboxProcessor.DEFAULT_OUTCOME, CLOCK);
            verify(repository).markDone(3, LEASE_UNTIL, "A".repeat(InboxProcessor.MAX_OUTCOME_LENGTH), CLOCK);
        }
    }

    @Nested
    @DisplayName("a notification whose handling fails")
    class Failed {

        @Test
        @DisplayName("is tried again after its backoff, keeping only the exception's type on the row")
        void retried() {
            RecordedNotification one = recorded(1, 2);
            when(repository.claimDue(NOW, LEASE_UNTIL, BATCH)).thenReturn(List.of(one));
            when(handler.handle(one)).thenThrow(new IllegalStateException("provider said acct_1 has no pi_123"));

            ProcessingBatchResult result = processor.processDue();

            assertThat(result).isEqualTo(new ProcessingBatchResult(1, 0, 1, 0, false));
            Instant next = CLOCK.plus(Duration.ofMinutes(2));
            verify(repository).reschedule(1, LEASE_UNTIL, 3, next, "IllegalStateException");
            verify(repository, never()).markDone(anyLong(), any(), any(), any());
            verify(events).publishEvent(new WebhookInboxEvents.RetryScheduled(1, "acme", "payment.paid", 3, 10, next,
                    "IllegalStateException: provider said acct_1 has no pi_123"));
        }

        @Test
        @DisplayName("is parked at its tenth failure, for an operator")
        void parked() {
            RecordedNotification one = recorded(1, 9);
            when(repository.claimDue(NOW, LEASE_UNTIL, BATCH)).thenReturn(List.of(one));
            when(handler.handle(one)).thenThrow(new IllegalStateException("still broken"));

            ProcessingBatchResult result = processor.processDue();

            assertThat(result).isEqualTo(new ProcessingBatchResult(1, 0, 0, 1, false));
            verify(repository).park(1, LEASE_UNTIL, 10, CLOCK, "IllegalStateException");
            verify(events).publishEvent(new WebhookInboxEvents.NotificationParked(1, "acme", "payment.paid", 10,
                    "IllegalStateException: still broken"));
        }

        @Test
        @DisplayName("holds back none of the others of its batch")
        void othersGoOn() {
            RecordedNotification failing = recorded(1, 0);
            RecordedNotification fine = recorded(2, 0);
            when(repository.claimDue(NOW, LEASE_UNTIL, BATCH)).thenReturn(List.of(failing, fine));
            when(handler.handle(failing)).thenThrow(new IllegalStateException("down"));
            when(handler.handle(fine)).thenReturn("APPLIED");

            assertThat(processor.processDue()).isEqualTo(new ProcessingBatchResult(2, 1, 1, 0, false));

            verify(repository).markDone(2, LEASE_UNTIL, "APPLIED", CLOCK);
        }
    }

    @Test
    @DisplayName("refuses a batch of nothing or a lease that would not keep a claim")
    void refusesNonsense() {
        assertThatThrownBy(() -> new InboxProcessor.Settings(0, LEASE)).hasMessageContaining("batch-size");
        assertThatThrownBy(() -> new InboxProcessor.Settings(1, null)).hasMessageContaining("lease");
        assertThatThrownBy(() -> new InboxProcessor.Settings(1, Duration.ZERO)).hasMessageContaining("lease");
        assertThatThrownBy(() -> new InboxProcessor.Settings(1, Duration.ofSeconds(-1))).hasMessageContaining("lease");
    }
}
