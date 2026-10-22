package com.ecclesiaflow.platform.events.retry.amqp;

import com.ecclesiaflow.platform.events.retry.DelayedRetryQueue;
import com.ecclesiaflow.platform.events.retry.DelayedRetryTopology;
import com.ecclesiaflow.platform.events.retry.RetryHop;
import com.ecclesiaflow.platform.events.retry.events.DelayedRetryEvents;
import com.ecclesiaflow.platform.events.retry.events.DelayedRetryEvents.DeadLetterCause;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DelayedRetryMessageRecovererTest {

    private static final String QUEUE = "auth.subscriber.member-account-status";
    private static final String EXCHANGE = "ecclesiaflow.domain-events";
    private static final String ROUTING_KEY = "members.account.deactivated";
    private static final RuntimeException KEYCLOAK_DOWN =
            new IllegalStateException("Keycloak unreachable at keycloak:8080");

    @Mock
    private DelayedRetryPublisher publisher;
    @Mock
    private ApplicationEventPublisher events;

    private final DelayedRetryTopology topology = new DelayedRetryTopology(QUEUE);
    private DelayedRetryMessageRecoverer recoverer() {
        return new DelayedRetryMessageRecoverer(new DelayedRetryTopologies(List.of(topology)), publisher, events);
    }

    private static Message firstDelivery(String consumerQueue) {
        return DelayedRetryTopologiesTest.message(consumerQueue, EXCHANGE, ROUTING_KEY, null);
    }

    private static Message backFromStage(int attempt) {
        return DelayedRetryTopologiesTest.message(QUEUE, "", QUEUE,
                new RetryHop(QUEUE, attempt, 1_790_000_000_000L, EXCHANGE, ROUTING_KEY));
    }

    private static ListenerExecutionFailedException listenerFailure(Message message, Throwable cause) {
        return new ListenerExecutionFailedException("Listener threw exception", cause, message);
    }

    private <T> T published(Class<T> type) {
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(event.capture());
        return type.cast(event.getValue());
    }

    @Nested
    @DisplayName("Moves a failed delivery to its next stage")
    class Schedules {

        @Test
        @DisplayName("the first failure goes to the 1 min stage, with the route it was published on")
        void firstFailureToFirstStage() {
            Message message = firstDelivery(QUEUE);

            recoverer().recover(message, listenerFailure(message, KEYCLOAK_DOWN));

            verify(publisher).publish(message, topology.retryQueue(1), EXCHANGE, ROUTING_KEY);
            DelayedRetryEvents.RetryScheduled event = published(DelayedRetryEvents.RetryScheduled.class);
            assertThat(event.queue()).isEqualTo(QUEUE);
            assertThat(event.attempt()).isEqualTo(1);
            assertThat(event.stages()).isEqualTo(4);
            assertThat(event.delay()).isEqualTo(Duration.ofMinutes(1));
            assertThat(event.retryQueue()).isEqualTo(QUEUE + ".retry.1m");
            assertThat(event.reason()).isEqualTo("IllegalStateException: Keycloak unreachable at keycloak:8080");
        }

        @Test
        @DisplayName("a redelivery from stage 2 goes to stage 3, keeping the route its hop carried back")
        void redeliveryToNextStage() {
            Message message = backFromStage(2);

            recoverer().recover(message, KEYCLOAK_DOWN);

            verify(publisher).publish(message, topology.retryQueue(3), EXCHANGE, ROUTING_KEY);
            assertThat(published(DelayedRetryEvents.RetryScheduled.class).delay()).isEqualTo(Duration.ofMinutes(30));
        }
    }

    @Nested
    @DisplayName("Dead-letters, by throwing a reject the container does not requeue")
    class DeadLetters {

        private void assertDeadLettered(Throwable thrown, Throwable cause) {
            assertThat(thrown).isInstanceOf(ListenerExecutionFailedException.class)
                    .cause().isInstanceOf(AmqpRejectAndDontRequeueException.class)
                    .cause().isSameAs(cause);
        }

        @Test
        @DisplayName("after the last stage has failed")
        void afterTheLastStage() {
            Message message = backFromStage(4);

            Throwable thrown = catchThrowable(() -> recoverer().recover(message, KEYCLOAK_DOWN));

            assertDeadLettered(thrown, KEYCLOAK_DOWN);
            verify(publisher, never()).publish(any(), any(), anyString(), anyString());
            DelayedRetryEvents.DeadLettered event = published(DelayedRetryEvents.DeadLettered.class);
            assertThat(event.cause()).isEqualTo(DeadLetterCause.EXHAUSTED);
            assertThat(event.retries()).isEqualTo(4);
            assertThat(((ListenerExecutionFailedException) thrown).getFailedMessage()).isSameAs(message);
        }

        @Test
        @DisplayName("at once for a queue without a topology, as RejectAndDontRequeueRecoverer does")
        void queueWithoutTopology() {
            Message message = firstDelivery("comm.subscriber.setup-token-issued");

            Throwable thrown = catchThrowable(() -> recoverer().recover(message, KEYCLOAK_DOWN));

            assertDeadLettered(thrown, KEYCLOAK_DOWN);
            assertThat(published(DelayedRetryEvents.DeadLettered.class))
                    .isEqualTo(new DelayedRetryEvents.DeadLettered("comm.subscriber.setup-token-issued",
                            DeadLetterCause.EXHAUSTED, 0, "IllegalStateException: Keycloak unreachable at keycloak:8080"));
        }

        @Test
        @DisplayName("at once for an unreadable message or an explicit reject: no wait changes them")
        void notRetryable() {
            Message message = backFromStage(1);
            RuntimeException unreadable = listenerFailure(message, new MessageConversionException("not protobuf"));
            RuntimeException rejected = new AmqpRejectAndDontRequeueException("poison");

            assertDeadLettered(catchThrowable(() -> recoverer().recover(message, unreadable)), unreadable);
            assertDeadLettered(catchThrowable(() -> recoverer().recover(message, rejected)), rejected);
            verify(publisher, never()).publish(any(), any(), anyString(), anyString());
        }

        @Test
        @DisplayName("at once for the other fatal causes Spring knows")
        void otherFatalCauses() {
            Message message = firstDelivery(QUEUE);

            for (Throwable fatal : List.of(new ClassCastException("x"), new NoSuchMethodException("x"),
                    new org.springframework.messaging.converter.MessageConversionException("x"))) {
                assertDeadLettered(catchThrowable(() -> recoverer().recover(message, fatal)), fatal);
            }
            verify(publisher, never()).publish(any(), any(), anyString(), anyString());
        }

        @Test
        @DisplayName("when the broker does not take the copy for the next stage")
        void publishFailed() {
            Message message = firstDelivery(QUEUE);
            DelayedRetryQueue stage = topology.retryQueue(1);
            RetryPublishFailedException refused = new RetryPublishFailedException(stage.getName(), "broker nack", null);
            when(publisher.publish(eq(message), eq(stage), anyString(), anyString())).thenThrow(refused);

            Throwable thrown = catchThrowable(() -> recoverer().recover(message, KEYCLOAK_DOWN));

            assertDeadLettered(thrown, refused);
            DelayedRetryEvents.DeadLettered event = published(DelayedRetryEvents.DeadLettered.class);
            assertThat(event.cause()).isEqualTo(DeadLetterCause.PUBLISH_FAILED);
            assertThat(event.reason()).contains("broker nack").contains("Keycloak unreachable");
        }

        @Test
        @DisplayName("describes a missing cause rather than failing")
        void withoutCause() {
            Message message = firstDelivery("comm.subscriber.setup-token-issued");

            catchThrowable(() -> recoverer().recover(message, null));

            assertThat(published(DelayedRetryEvents.DeadLettered.class).reason()).isEqualTo("no cause");
        }
    }
}
