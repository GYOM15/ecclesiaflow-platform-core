package com.ecclesiaflow.platform.events.outbox.relay;

import com.ecclesiaflow.platform.events.outbox.OutboxMessage;
import com.ecclesiaflow.platform.events.outbox.amqp.AmqpOutboxMessageMapper;
import com.ecclesiaflow.platform.events.outbox.events.OutboxRelayEvents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.amqp.AmqpApplicationContextClosedException;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;

import java.net.ConnectException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final Instant LEASE_UNTIL = NOW.plus(LEASE);
    private static final String EXCHANGE = "ecclesiaflow.domain-events";
    private static final int BATCH = 3;

    private final OutboxRetryPolicy retryPolicy =
            new OutboxRetryPolicy(3, Duration.ofSeconds(5), 2.0, Duration.ofMinutes(1));

    @Mock
    private OutboxRepository repository;
    @Mock
    private RabbitTemplate rabbitTemplate;
    @Mock
    private ApplicationEventPublisher events;

    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        relay = relay(Duration.ofMillis(200));
    }

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    private OutboxRelay relay(Duration confirmTimeout) {
        return new OutboxRelay(repository, rabbitTemplate,
                new AmqpOutboxMessageMapper(mock(MessageConverter.class)), retryPolicy,
                new OutboxRelay.Settings(BATCH, confirmTimeout, LEASE), events, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ClaimedOutboxMessage claimed(long id, int attempts) {
        return new ClaimedOutboxMessage(id, attempts, new OutboxMessage(EXCHANGE, "rk." + id, new byte[]{(byte) id},
                "application/x-protobuf", null, null, Map.of("__TypeId__", "a.B")));
    }

    private void given(ClaimedOutboxMessage... rows) {
        when(repository.claimDue(NOW, LEASE_UNTIL, BATCH)).thenReturn(List.of(rows));
    }

    private static Answer<Void> ack() {
        return invocation -> {
            invocation.<CorrelationData>getArgument(3).getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        };
    }

    private static Answer<Void> nack(String reason) {
        return invocation -> {
            invocation.<CorrelationData>getArgument(3).getFuture().complete(new CorrelationData.Confirm(false, reason));
            return null;
        };
    }

    private static Answer<Void> unroutable() {
        return invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.setReturned(new ReturnedMessage(new Message(new byte[0], new MessageProperties()),
                    312, "NO_ROUTE", EXCHANGE, invocation.getArgument(1)));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        };
    }

    private static Answer<Void> silent() {
        return invocation -> null;
    }

    private void broker(long id, Answer<Void> behaviour) {
        doAnswer(behaviour).when(rabbitTemplate)
                .send(eq(EXCHANGE), eq("rk." + id), any(Message.class), any(CorrelationData.class));
    }

    @Nested
    @DisplayName("nothing due")
    class NothingDue {

        @Test
        @DisplayName("Publishes nothing and writes nothing")
        void idle() {
            given();

            RelayBatchResult result = relay.relayDue();

            assertThat(result).isEqualTo(RelayBatchResult.EMPTY);
            assertThat(result.batchWasFull()).isFalse();
            verifyNoInteractions(rabbitTemplate, events);
            verify(repository).claimDue(NOW, LEASE_UNTIL, BATCH);
            verifyNoMoreInteractions(repository);
        }
    }

    @Nested
    @DisplayName("broker confirms")
    class Confirmed {

        @Test
        @DisplayName("Marks every confirmed message sent and reports each one")
        void marksConfirmedSent() {
            given(claimed(1, 0), claimed(2, 1));
            broker(1, ack());
            broker(2, ack());

            RelayBatchResult result = relay.relayDue();

            verify(repository).markSent(List.of(1L, 2L), NOW);
            verify(repository, never()).reschedule(anyLong(), any(), anyInt(), any(), any());
            verify(events).publishEvent(new OutboxRelayEvents.MessageRelayed(1, EXCHANGE, "rk.1", 1));
            verify(events).publishEvent(new OutboxRelayEvents.MessageRelayed(2, EXCHANGE, "rk.2", 2));
            assertThat(result).isEqualTo(new RelayBatchResult(2, 2, 0, 0, 0, false));
        }

        @Test
        @DisplayName("Publishes the staged bytes and headers, correlated to the outbox row")
        void publishesStagedMessage() {
            given(claimed(5, 0));
            broker(5, ack());

            relay.relayDue();

            ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
            ArgumentCaptor<CorrelationData> correlation = ArgumentCaptor.forClass(CorrelationData.class);
            verify(rabbitTemplate).send(eq(EXCHANGE), eq("rk.5"), message.capture(), correlation.capture());
            assertThat(message.getValue().getBody()).containsExactly(5);
            assertThat((String) message.getValue().getMessageProperties().getHeader("__TypeId__")).isEqualTo("a.B");
            assertThat(correlation.getValue().getId()).isEqualTo("outbox-5");
        }

        @Test
        @DisplayName("Reports a full batch so the caller drains the backlog without waiting")
        void reportsFullBatch() {
            given(claimed(1, 0), claimed(2, 0), claimed(3, 0));
            broker(1, ack());
            broker(2, ack());
            broker(3, ack());

            assertThat(relay.relayDue().batchWasFull()).isTrue();
        }

        @Test
        @DisplayName("Writes every outcome before telling listeners about it")
        void writesBeforeNotifying() {
            given(claimed(1, 0));
            broker(1, ack());

            relay.relayDue();

            InOrder order = inOrder(repository, events);
            order.verify(repository).markSent(List.of(1L), NOW);
            order.verify(events).publishEvent(any(OutboxRelayEvents.MessageRelayed.class));
        }
    }

    @Nested
    @DisplayName("message-level failures")
    class MessageFailures {

        @Test
        @DisplayName("Retries a nacked message with a backoff, counting the attempt")
        void retriesNack() {
            given(claimed(1, 0));
            broker(1, nack("internal error"));

            RelayBatchResult result = relay.relayDue();

            verify(repository).reschedule(eq(1L), eq(LEASE_UNTIL), eq(1), eq(NOW.plusSeconds(5)),
                    argThat(reason -> reason.contains("nack") && reason.contains("internal error")));
            verify(repository, never()).markSent(any(), any());
            verify(events).publishEvent(argThat((Object e) -> e instanceof OutboxRelayEvents.RetryScheduled r
                    && r.outboxId() == 1 && r.attempts() == 1 && r.maxAttempts() == 3
                    && r.nextAttemptAt().equals(NOW.plusSeconds(5))));
            assertThat(result).isEqualTo(new RelayBatchResult(1, 0, 1, 0, 0, false));
        }

        @Test
        @DisplayName("Retries a nack that gives no reason")
        void retriesNackWithoutReason() {
            given(claimed(1, 0));
            broker(1, nack(null));

            relay.relayDue();

            verify(repository).reschedule(eq(1L), eq(LEASE_UNTIL), eq(1), eq(NOW.plusSeconds(5)),
                    eq("broker nack"));
        }

        @Test
        @DisplayName("Retries a message no queue is bound for")
        void retriesUnroutable() {
            given(claimed(1, 0));
            broker(1, unroutable());

            relay.relayDue();

            verify(repository).reschedule(eq(1L), eq(LEASE_UNTIL), eq(1), eq(NOW.plusSeconds(5)),
                    argThat(reason -> reason.contains("unroutable") && reason.contains("312 NO_ROUTE")));
        }

        @Test
        @DisplayName("Retries a message whose confirm does not arrive in time")
        void retriesTimeout() {
            relay = relay(Duration.ofMillis(30));
            given(claimed(1, 0), claimed(2, 0));
            broker(1, silent());
            broker(2, silent());

            relay.relayDue();

            verify(repository).reschedule(eq(1L), eq(LEASE_UNTIL), eq(1), eq(NOW.plusSeconds(5)),
                    argThat(reason -> reason.contains("no broker confirm within PT0.03S")));
            verify(repository).reschedule(eq(2L), eq(LEASE_UNTIL), eq(1), eq(NOW.plusSeconds(5)),
                    argThat(reason -> reason.contains("no broker confirm")));
        }

        @Test
        @DisplayName("Retries a message whose confirm failed")
        void retriesFailedConfirm() {
            given(claimed(1, 0));
            broker(1, invocation -> {
                invocation.<CorrelationData>getArgument(3).getFuture()
                        .completeExceptionally(new IllegalStateException("channel closed"));
                return null;
            });

            relay.relayDue();

            verify(repository).reschedule(eq(1L), eq(LEASE_UNTIL), eq(1), eq(NOW.plusSeconds(5)),
                    argThat(reason -> reason.contains("IllegalStateException") && reason.contains("channel closed")));
        }

        @Test
        @DisplayName("Keeps relaying the rest of the batch when one send throws")
        void isolatesOneFailingSend() {
            given(claimed(1, 0), claimed(2, 0));
            doThrow(new AmqpException("bad message")).when(rabbitTemplate)
                    .send(eq(EXCHANGE), eq("rk.1"), any(Message.class), any(CorrelationData.class));
            broker(2, ack());

            RelayBatchResult result = relay.relayDue();

            verify(repository).markSent(List.of(2L), NOW);
            verify(repository).reschedule(eq(1L), eq(LEASE_UNTIL), eq(1), eq(NOW.plusSeconds(5)),
                    argThat(reason -> reason.contains("bad message")));
            assertThat(result).isEqualTo(new RelayBatchResult(2, 1, 1, 0, 0, false));
        }

        @Test
        @DisplayName("Grows the backoff with the attempts already made")
        void growsBackoff() {
            given(claimed(1, 1));
            broker(1, nack("again"));

            relay.relayDue();

            verify(repository).reschedule(eq(1L), eq(LEASE_UNTIL), eq(2), eq(NOW.plusSeconds(10)), anyString());
        }

        @Test
        @DisplayName("Parks a message once its attempts are exhausted")
        void parksExhausted() {
            given(claimed(1, 2));
            broker(1, unroutable());

            RelayBatchResult result = relay.relayDue();

            verify(repository).park(eq(1L), eq(LEASE_UNTIL), eq(3), eq(NOW), argThat(r -> r.contains("unroutable")));
            verify(repository, never()).reschedule(anyLong(), any(), anyInt(), any(), any());
            verify(events).publishEvent(argThat((Object e) -> e instanceof OutboxRelayEvents.MessageParked p
                    && p.outboxId() == 1 && p.attempts() == 3 && p.routingKey().equals("rk.1")));
            assertThat(result).isEqualTo(new RelayBatchResult(1, 0, 0, 1, 0, false));
        }
    }

    @Nested
    @DisplayName("broker unavailable")
    class BrokerUnavailable {

        @Test
        @DisplayName("Stops at the first connection failure and defers the batch without counting attempts")
        void defersWithoutCharging() {
            given(claimed(1, 0), claimed(2, 2), claimed(3, 1));
            doThrow(new AmqpConnectException(new ConnectException("Connection refused"))).when(rabbitTemplate)
                    .send(eq(EXCHANGE), eq("rk.1"), any(Message.class), any(CorrelationData.class));

            RelayBatchResult result = relay.relayDue();

            verify(rabbitTemplate, times(1)).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
            verify(repository).reschedule(eq(1L), eq(LEASE_UNTIL), eq(0), eq(NOW.plusSeconds(5)),
                    argThat(r -> r.contains("Connection refused")));
            verify(repository).reschedule(eq(2L), eq(LEASE_UNTIL), eq(2), eq(NOW.plusSeconds(5)), anyString());
            verify(repository).reschedule(eq(3L), eq(LEASE_UNTIL), eq(1), eq(NOW.plusSeconds(5)), anyString());
            verify(repository, never()).park(anyLong(), any(), anyInt(), any(), any());
            verify(events).publishEvent(argThat((Object e) -> e instanceof OutboxRelayEvents.MessagesDeferred d
                    && d.count() == 3 && d.nextAttemptAt().equals(NOW.plusSeconds(5))));
            assertThat(result).isEqualTo(new RelayBatchResult(3, 0, 0, 0, 3, true));
        }

        @Test
        @DisplayName("Defers the batch when the application is shutting down")
        void defersOnContextClose() {
            given(claimed(1, 0));
            doThrow(new AmqpApplicationContextClosedException("closing")).when(rabbitTemplate)
                    .send(eq(EXCHANGE), eq("rk.1"), any(Message.class), any(CorrelationData.class));

            RelayBatchResult result = relay.relayDue();

            verify(repository).reschedule(eq(1L), eq(LEASE_UNTIL), eq(0), eq(NOW.plusSeconds(5)), anyString());
            assertThat(result.deferred()).isEqualTo(1);
        }

        @Test
        @DisplayName("Still records what was confirmed before the connection failed")
        void keepsEarlierConfirms() {
            given(claimed(1, 0), claimed(2, 0));
            broker(1, ack());
            doThrow(new AmqpConnectException(new ConnectException("refused"))).when(rabbitTemplate)
                    .send(eq(EXCHANGE), eq("rk.2"), any(Message.class), any(CorrelationData.class));

            RelayBatchResult result = relay.relayDue();

            verify(repository).markSent(List.of(1L), NOW);
            verify(repository).reschedule(eq(2L), eq(LEASE_UNTIL), eq(0), eq(NOW.plusSeconds(5)), anyString());
            assertThat(result).isEqualTo(new RelayBatchResult(2, 1, 0, 0, 1, false));
        }
    }

    @Nested
    @DisplayName("interruption")
    class Interruption {

        @Test
        @DisplayName("Hands unconfirmed messages back at once, records the rest, and stays interrupted")
        void releasesOnInterrupt() {
            given(claimed(1, 0), claimed(2, 0));
            broker(1, ack());
            broker(2, silent());
            AtomicBoolean interruptedWhileWriting = new AtomicBoolean(true);
            doAnswer(invocation -> {
                interruptedWhileWriting.set(Thread.currentThread().isInterrupted());
                return null;
            }).when(repository).markSent(List.of(1L), NOW);

            Thread.currentThread().interrupt();
            RelayBatchResult result = relay.relayDue();

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(interruptedWhileWriting).isFalse();
            verify(repository).reschedule(eq(2L), eq(LEASE_UNTIL), eq(0), eq(NOW),
                    argThat(r -> r.contains("interrupted")));
            assertThat(result).isEqualTo(new RelayBatchResult(2, 1, 0, 0, 1, false));
        }

        @Test
        @DisplayName("Restores the interrupt even when writing an outcome fails")
        void restoresInterruptOnFailure() {
            given(claimed(1, 0));
            broker(1, silent());
            doThrow(new DataAccessResourceFailureException("db down")).when(repository)
                    .reschedule(anyLong(), any(), anyInt(), any(), any());

            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> relay.relayDue()).isInstanceOf(DataAccessResourceFailureException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        }
    }

    @Nested
    @DisplayName("database failure")
    class DatabaseFailure {

        @Test
        @DisplayName("Lets a failed write surface; the lease hands the rows back later")
        void surfacesWriteFailure() {
            given(claimed(1, 0));
            broker(1, ack());
            doThrow(new DataAccessResourceFailureException("db down")).when(repository).markSent(any(), any());

            assertThatThrownBy(() -> relay.relayDue()).isInstanceOf(DataAccessResourceFailureException.class);
            verifyNoInteractions(events);
        }
    }

    @Nested
    @DisplayName("settings")
    class SettingsValidation {

        @Test
        @DisplayName("Rejects a lease that a confirm wait could outlast")
        void leaseMustOutlastConfirmWait() {
            assertThatThrownBy(() -> new OutboxRelay.Settings(10, Duration.ofSeconds(5), Duration.ofSeconds(5)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lease");
            assertThatThrownBy(() -> new OutboxRelay.Settings(10, Duration.ofSeconds(5), null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lease");
        }

        @Test
        @DisplayName("Rejects an empty batch or a missing confirm timeout")
        void rejectsInvalidSettings() {
            assertThatThrownBy(() -> new OutboxRelay.Settings(0, Duration.ofSeconds(5), Duration.ofMinutes(1)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("batch-size");
            assertThatThrownBy(() -> new OutboxRelay.Settings(10, Duration.ZERO, Duration.ofMinutes(1)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("confirm-timeout");
            assertThatThrownBy(() -> new OutboxRelay.Settings(10, null, Duration.ofMinutes(1)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("confirm-timeout");
        }
    }
}
