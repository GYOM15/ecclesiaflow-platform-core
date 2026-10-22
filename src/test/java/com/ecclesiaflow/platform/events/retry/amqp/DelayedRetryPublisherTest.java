package com.ecclesiaflow.platform.events.retry.amqp;

import com.ecclesiaflow.platform.events.retry.DelayedRetryQueue;
import com.ecclesiaflow.platform.events.retry.DelayedRetryStamp;
import com.ecclesiaflow.platform.events.retry.DelayedRetryTopology;
import com.ecclesiaflow.platform.events.retry.RetryHop;
import com.ecclesiaflow.platform.events.signing.DomainEventSigner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DelayedRetryPublisherTest {

    private static final String SECRET = "publisher-secret";
    private static final String QUEUE = "auth.subscriber.member-account-status";
    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");
    private static final byte[] BODY = "deactivated".getBytes(StandardCharsets.UTF_8);

    private final RabbitTemplate template = mock(RabbitTemplate.class);
    private final DelayedRetryStamp stamp = new DelayedRetryStamp(SECRET);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final DelayedRetryQueue stage = new DelayedRetryTopology(QUEUE).retryQueue(2);
    private final DelayedRetryPublisher publisher =
            new DelayedRetryPublisher(template, stamp, Duration.ofMillis(200), clock);

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    private void brokerAnswers(Consumer<CorrelationData> answer) {
        doAnswer(invocation -> {
            answer.accept(invocation.getArgument(3));
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    private static void ack(CorrelationData correlation) {
        correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
    }

    private static Message failed() {
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/x-protobuf");
        properties.setMessageId("m-1");
        properties.setReceivedDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setExpiration("60000");
        properties.setHeader("__TypeId__", "com.ecclesiaflow.grpc.events.members.MemberDeactivatedEvent");
        properties.setHeader(DomainEventSigner.SIGNATURE_HEADER, "c2lnbmF0dXJl");
        properties.setHeader(DomainEventSigner.SIGNED_AT_HEADER, "1790000000000");
        properties.setHeader(DomainEventSigner.SIGNATURE_VERSION_HEADER, "1");
        properties.setHeader("x-death", List.of(Map.of("queue", QUEUE + ".retry.1m", "reason", "expired")));
        properties.setHeader("x-first-death-queue", QUEUE + ".retry.1m");
        properties.setHeader("x-last-death-reason", "expired");
        properties.setHeader(RetryHop.ATTEMPT_HEADER, "1");
        properties.setHeader(RetryHop.STAMP_HEADER, "b2xk");
        return new Message(BODY, properties);
    }

    @Nested
    @DisplayName("Once the broker confirms the copy")
    class Confirmed {

        private Message sent() {
            brokerAnswers(DelayedRetryPublisherTest::ack);
            publisher.publish(failed(), stage, "ecclesiaflow.domain-events", "members.account.deactivated");
            ArgumentCaptor<Message> copy = ArgumentCaptor.forClass(Message.class);
            verify(template).send(eq(""), eq(QUEUE + ".retry.5m"), copy.capture(), any(CorrelationData.class));
            return copy.getValue();
        }

        @Test
        @DisplayName("publishes it to the stage through the default exchange, with a correlation of its own")
        void publishesToTheStage() {
            brokerAnswers(DelayedRetryPublisherTest::ack);

            RetryHop hop = publisher.publish(failed(), stage, "ecclesiaflow.domain-events",
                    "members.account.deactivated");

            ArgumentCaptor<CorrelationData> correlation = ArgumentCaptor.forClass(CorrelationData.class);
            verify(template).send(eq(""), eq(QUEUE + ".retry.5m"), any(Message.class), correlation.capture());
            assertThat(correlation.getValue().getId()).startsWith(DelayedRetryPublisher.CORRELATION_PREFIX);
            assertThat(hop).isEqualTo(new RetryHop(QUEUE, 2, NOW.toEpochMilli(), "ecclesiaflow.domain-events",
                    "members.account.deactivated"));
        }

        @Test
        @DisplayName("keeps the body, the type and the event's signature headers untouched")
        void keepsTheEvent() {
            Message copy = sent();
            MessageProperties properties = copy.getMessageProperties();

            assertThat(copy.getBody()).isEqualTo(BODY);
            assertThat(properties.getContentType()).isEqualTo("application/x-protobuf");
            assertThat(properties.getMessageId()).isEqualTo("m-1");
            assertThat(properties.getHeaders()).containsEntry("__TypeId__",
                            "com.ecclesiaflow.grpc.events.members.MemberDeactivatedEvent")
                    .containsEntry(DomainEventSigner.SIGNATURE_HEADER, "c2lnbmF0dXJl")
                    .containsEntry(DomainEventSigner.SIGNED_AT_HEADER, "1790000000000")
                    .containsEntry(DomainEventSigner.SIGNATURE_VERSION_HEADER, "1");
        }

        @Test
        @DisplayName("makes it persistent, without a per-message expiry, and drops the broker's death headers")
        void makesItDurable() {
            MessageProperties properties = sent().getMessageProperties();

            assertThat(properties.getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
            assertThat(properties.getExpiration()).isNull();
            assertThat(properties.getHeaders()).doesNotContainKeys("x-death", "x-first-death-queue",
                    "x-last-death-reason");
        }

        @Test
        @DisplayName("replaces the previous hop with the new one, under a stamp that vouches for it")
        void stampsTheNewHop() {
            MessageProperties properties = sent().getMessageProperties();
            RetryHop hop = RetryHop.fromHeaders(properties.getHeaders()).orElseThrow();

            assertThat(hop.attempt()).isEqualTo(2);
            assertThat(hop.retriedAt()).isEqualTo(NOW.toEpochMilli());
            assertThat(stamp.matches(hop, "1790000000000", "c2lnbmF0dXJl", BODY,
                    (String) properties.getHeader(RetryHop.STAMP_HEADER))).isTrue();
        }

        @Test
        @DisplayName("writes no stamp when signing is off")
        void noStampWithoutSecret() {
            DelayedRetryPublisher unsigned = new DelayedRetryPublisher(template, new DelayedRetryStamp(""),
                    Duration.ofMillis(200), clock);
            brokerAnswers(DelayedRetryPublisherTest::ack);

            unsigned.publish(failed(), stage, "ecclesiaflow.domain-events", "members.account.deactivated");

            ArgumentCaptor<Message> copy = ArgumentCaptor.forClass(Message.class);
            verify(template).send(anyString(), anyString(), copy.capture(), any(CorrelationData.class));
            assertThat(copy.getValue().getMessageProperties().getHeaders())
                    .doesNotContainKey(RetryHop.STAMP_HEADER)
                    .containsEntry(RetryHop.ATTEMPT_HEADER, "2");
        }
    }

    @Nested
    @DisplayName("Fails, so the delivery is dead-lettered instead of acknowledged")
    class Failing {

        @Test
        @DisplayName("when the broker nacks the copy")
        void onNack() {
            brokerAnswers(correlation -> correlation.getFuture().complete(new CorrelationData.Confirm(false, "full")));

            assertThatThrownBy(() -> publisher.publish(failed(), stage, "x", "k"))
                    .isInstanceOf(RetryPublishFailedException.class)
                    .hasMessageContaining("broker nack: full")
                    .extracting(e -> ((RetryPublishFailedException) e).getRetryQueue())
                    .isEqualTo(QUEUE + ".retry.5m");
        }

        @Test
        @DisplayName("when the broker nacks without a reason")
        void onBareNack() {
            brokerAnswers(correlation -> correlation.getFuture().complete(new CorrelationData.Confirm(false, null)));

            assertThatThrownBy(() -> publisher.publish(failed(), stage, "x", "k"))
                    .isInstanceOf(RetryPublishFailedException.class)
                    .hasMessageEndingWith("broker nack");
        }

        @Test
        @DisplayName("when the stage is missing and the broker returns the copy")
        void onReturn() {
            brokerAnswers(correlation -> {
                correlation.setReturned(new ReturnedMessage(new Message(BODY), 312, "NO_ROUTE", "", "q.retry.5m"));
                ack(correlation);
            });

            assertThatThrownBy(() -> publisher.publish(failed(), stage, "x", "k"))
                    .isInstanceOf(RetryPublishFailedException.class)
                    .hasMessageContaining("312 NO_ROUTE");
        }

        @Test
        @DisplayName("when no confirm arrives in time")
        void onTimeout() {
            assertThatThrownBy(() -> publisher.publish(failed(), stage, "x", "k"))
                    .isInstanceOf(RetryPublishFailedException.class)
                    .hasMessageContaining("no broker confirm within");
        }

        @Test
        @DisplayName("when the confirm fails")
        void onFailedConfirm() {
            brokerAnswers(correlation -> correlation.getFuture().completeExceptionally(new IllegalStateException("closed")));

            assertThatThrownBy(() -> publisher.publish(failed(), stage, "x", "k"))
                    .isInstanceOf(RetryPublishFailedException.class)
                    .hasMessageContaining("IllegalStateException: closed");
        }

        @Test
        @DisplayName("when the broker cannot be reached")
        void onSendFailure() {
            doThrow(new AmqpConnectException(new java.net.ConnectException("refused")))
                    .when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

            assertThatThrownBy(() -> publisher.publish(failed(), stage, "x", "k"))
                    .isInstanceOf(RetryPublishFailedException.class)
                    .hasCauseInstanceOf(AmqpConnectException.class);
        }

        @Test
        @DisplayName("when interrupted, keeping the interrupt flag")
        void onInterrupt() {
            Thread.currentThread().interrupt();

            assertThatThrownBy(() -> publisher.publish(failed(), stage, "x", "k"))
                    .isInstanceOf(RetryPublishFailedException.class)
                    .hasMessageContaining("interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        }
    }

    @Nested
    @DisplayName("Built on a connection factory")
    class Built {

        private final ConnectionFactory factory = mock(ConnectionFactory.class);

        @Test
        @DisplayName("refuses one without correlated confirms or returns")
        void refusesAnUnreliableFactory() {
            when(factory.isPublisherReturns()).thenReturn(true);
            assertThatThrownBy(() -> DelayedRetryPublisher.on(factory, stamp, Duration.ofSeconds(5), clock))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("publisher-confirm-type=correlated");

            when(factory.isPublisherConfirms()).thenReturn(true);
            when(factory.isPublisherReturns()).thenReturn(false);
            assertThatThrownBy(() -> DelayedRetryPublisher.on(factory, stamp, Duration.ofSeconds(5), clock))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("publisher-returns=true");
        }

        @Test
        @DisplayName("accepts one that confirms and returns")
        void acceptsAReliableFactory() {
            when(factory.isPublisherConfirms()).thenReturn(true);
            when(factory.isPublisherReturns()).thenReturn(true);

            assertThat(DelayedRetryPublisher.on(factory, stamp, Duration.ofSeconds(5), clock)).isNotNull();
        }

        @Test
        @DisplayName("refuses a confirm timeout that is not positive")
        void refusesANonPositiveTimeout() {
            assertThatThrownBy(() -> new DelayedRetryPublisher(template, stamp, Duration.ZERO, clock))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new DelayedRetryPublisher(template, stamp, null, clock))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
