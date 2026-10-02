package com.ecclesiaflow.platform.events.outbox.amqp;

import com.ecclesiaflow.platform.events.outbox.OutboxMessage;
import com.ecclesiaflow.platform.events.signing.DomainEventSigner;
import com.ecclesiaflow.platform.events.signing.amqp.SigningMessagePostProcessor;
import com.google.protobuf.StringValue;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.amqp.support.converter.MessageConverter;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AmqpOutboxMessageMapperTest {

    private static final String EXCHANGE = "ecclesiaflow.domain-events";
    private static final String ROUTING_KEY = "church.member.removed-from-group.v1";

    /** Same wire shape as the protobuf converter every publishing module ships. */
    static final class FleetProtobufConverter implements MessageConverter {
        @Override
        public Message toMessage(Object object, MessageProperties properties) {
            com.google.protobuf.Message proto = (com.google.protobuf.Message) object;
            properties.setContentType("application/x-protobuf");
            properties.setHeader("__TypeId__", object.getClass().getName());
            return new Message(proto.toByteArray(), properties);
        }

        @Override
        public Object fromMessage(Message message) {
            throw new UnsupportedOperationException();
        }
    }

    /** A converter that sets one extra property, to prove the mapper refuses what it cannot replay. */
    private static MessageConverter converterSetting(Consumer<MessageProperties> extra) {
        return new MessageConverter() {
            @Override
            public Message toMessage(Object object, MessageProperties properties) {
                extra.accept(properties);
                return new Message(new byte[]{1}, properties);
            }

            @Override
            public Object fromMessage(Message message) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private final AmqpOutboxMessageMapper mapper = new AmqpOutboxMessageMapper(new FleetProtobufConverter());

    @Nested
    @DisplayName("toOutboxMessage")
    class ToOutboxMessage {

        @Test
        @DisplayName("Captures the body and the properties the converter set")
        void capturesConvertedMessage() {
            StringValue event = StringValue.of("member-42");

            OutboxMessage staged = mapper.toOutboxMessage(EXCHANGE, ROUTING_KEY, event);

            assertThat(staged.exchange()).isEqualTo(EXCHANGE);
            assertThat(staged.routingKey()).isEqualTo(ROUTING_KEY);
            assertThat(staged.payload()).isEqualTo(event.toByteArray());
            assertThat(staged.contentType()).isEqualTo("application/x-protobuf");
            assertThat(staged.contentEncoding()).isNull();
            assertThat(staged.messageId()).isNull();
            assertThat(staged.headers()).containsExactly(Map.entry("__TypeId__", StringValue.class.getName()));
        }

        @Test
        @DisplayName("Captures a content encoding and a message id when the converter sets them")
        void capturesEncodingAndMessageId() {
            AmqpOutboxMessageMapper withIds = new AmqpOutboxMessageMapper(converterSetting(p -> {
                p.setContentEncoding("UTF-8");
                p.setMessageId("id-1");
            }));

            OutboxMessage staged = withIds.toOutboxMessage(EXCHANGE, ROUTING_KEY, "x");

            assertThat(staged.contentEncoding()).isEqualTo("UTF-8");
            assertThat(staged.messageId()).isEqualTo("id-1");
        }

        @Test
        @DisplayName("Lets a conversion failure surface to the caller")
        void conversionFailureSurfaces() {
            assertThatThrownBy(() -> mapper.toOutboxMessage(EXCHANGE, ROUTING_KEY, "not a protobuf"))
                    .isInstanceOf(ClassCastException.class);
            AmqpOutboxMessageMapper failing = new AmqpOutboxMessageMapper(converterSetting(p -> {
                throw new MessageConversionException("boom");
            }));
            assertThatThrownBy(() -> failing.toOutboxMessage(EXCHANGE, ROUTING_KEY, "x"))
                    .isInstanceOf(MessageConversionException.class);
        }

        @Test
        @DisplayName("Refuses a non-text header, which could not be replayed with its type")
        void rejectsNonTextHeader() {
            AmqpOutboxMessageMapper typed = new AmqpOutboxMessageMapper(converterSetting(p -> p.setHeader("retries", 3)));

            assertThatThrownBy(() -> typed.toOutboxMessage(EXCHANGE, ROUTING_KEY, "x"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("retries");
        }

        @Test
        @DisplayName("Refuses a header without a value")
        void rejectsNullHeader() {
            AmqpOutboxMessageMapper withNull = new AmqpOutboxMessageMapper(
                    converterSetting(p -> p.getHeaders().put("x-empty", null)));

            assertThatThrownBy(() -> withNull.toOutboxMessage(EXCHANGE, ROUTING_KEY, "x"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("x-empty");
        }

        @Test
        @DisplayName("Refuses every AMQP property the outbox does not store")
        void rejectsUnstoredProperties() {
            Map<String, Consumer<MessageProperties>> unstored = Map.of(
                    "type", p -> p.setType("t"),
                    "correlationId", p -> p.setCorrelationId("c"),
                    "replyTo", p -> p.setReplyTo("r"),
                    "expiration", p -> p.setExpiration("1000"),
                    "timestamp", p -> p.setTimestamp(new Date()),
                    "userId", p -> p.setUserId("u"),
                    "appId", p -> p.setAppId("a"),
                    "clusterId", p -> p.setClusterId("c"),
                    "priority", p -> p.setPriority(5),
                    "deliveryMode", p -> p.setDeliveryMode(MessageDeliveryMode.NON_PERSISTENT));

            unstored.forEach((property, setter) ->
                    assertThatThrownBy(() -> new AmqpOutboxMessageMapper(converterSetting(setter))
                            .toOutboxMessage(EXCHANGE, ROUTING_KEY, "x"))
                            .as(property)
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining(property));
        }
    }

    @Nested
    @DisplayName("toAmqpMessage")
    class ToAmqpMessage {

        @Test
        @DisplayName("Rebuilds the body, properties and headers that were staged")
        void rebuildsStagedMessage() {
            OutboxMessage staged = new OutboxMessage(EXCHANGE, ROUTING_KEY, new byte[]{7, 8},
                    "application/x-protobuf", "gzip", "id-9", Map.of("__TypeId__", "a.B"));

            Message message = mapper.toAmqpMessage(staged);

            assertThat(message.getBody()).containsExactly(7, 8);
            MessageProperties properties = message.getMessageProperties();
            assertThat(properties.getContentType()).isEqualTo("application/x-protobuf");
            assertThat(properties.getContentEncoding()).isEqualTo("gzip");
            assertThat(properties.getMessageId()).isEqualTo("id-9");
            assertThat(properties.getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
            assertThat(properties.getHeaders()).containsExactly(Map.entry("__TypeId__", "a.B"));
        }
    }

    @Nested
    @DisplayName("relayed message against a direct publish")
    class RelayedAgainstDirect {

        private static final Instant SIGNED_AT = Instant.parse("2026-10-02T09:00:00Z");

        private record Published(String exchange, String routingKey, boolean mandatory,
                                 AMQP.BasicProperties properties, byte[] body) {
        }

        private Channel channel;
        private RabbitTemplate template;

        // Wired as church and members wire their domain-events template; one instance, as in a module.
        @BeforeEach
        void moduleTemplate() throws Exception {
            ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
            Connection connection = mock(Connection.class);
            channel = mock(Channel.class);
            when(connectionFactory.createConnection()).thenReturn(connection);
            when(connection.createChannel(false)).thenReturn(channel);

            template = new RabbitTemplate(connectionFactory);
            template.setMessageConverter(new FleetProtobufConverter());
            template.setExchange(EXCHANGE);
            template.setMandatory(true);
            template.setReturnsCallback(returned -> { });
            template.addBeforePublishPostProcessors(new SigningMessagePostProcessor(
                    new DomainEventSigner("relay-identity-secret"), Clock.fixed(SIGNED_AT, ZoneOffset.UTC)));
        }

        private List<Published> published(int count) throws Exception {
            ArgumentCaptor<AMQP.BasicProperties> properties = ArgumentCaptor.forClass(AMQP.BasicProperties.class);
            ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
            ArgumentCaptor<String> exchange = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> routingKey = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Boolean> mandatory = ArgumentCaptor.forClass(Boolean.class);
            verify(channel, times(count)).basicPublish(exchange.capture(), routingKey.capture(),
                    mandatory.capture(), properties.capture(), body.capture());
            List<Published> published = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                published.add(new Published(exchange.getAllValues().get(i), routingKey.getAllValues().get(i),
                        mandatory.getAllValues().get(i), properties.getAllValues().get(i), body.getAllValues().get(i)));
            }
            return published;
        }

        private void relay(StringValue event) {
            template.send(EXCHANGE, ROUTING_KEY,
                    mapper.toAmqpMessage(mapper.toOutboxMessage(EXCHANGE, ROUTING_KEY, event)),
                    new CorrelationData("outbox-1"));
        }

        @Test
        @DisplayName("Puts the same bytes, properties and signature on the wire")
        void relayedMessageIsIdenticalToDirectPublish() throws Exception {
            StringValue event = StringValue.of("member-42");

            template.convertAndSend(ROUTING_KEY, event, new CorrelationData("event-id"));
            relay(event);
            Published direct = published(2).get(0);
            Published relayed = published(2).get(1);

            assertThat(relayed.exchange()).isEqualTo(direct.exchange()).isEqualTo(EXCHANGE);
            assertThat(relayed.routingKey()).isEqualTo(direct.routingKey()).isEqualTo(ROUTING_KEY);
            assertThat(relayed.mandatory()).isEqualTo(direct.mandatory()).isTrue();
            assertThat(relayed.body()).isEqualTo(direct.body());
            assertThat(relayed.properties().getContentType()).isEqualTo(direct.properties().getContentType());
            assertThat(relayed.properties().getContentEncoding()).isEqualTo(direct.properties().getContentEncoding());
            assertThat(relayed.properties().getDeliveryMode()).isEqualTo(direct.properties().getDeliveryMode());
            assertThat(relayed.properties().getPriority()).isEqualTo(direct.properties().getPriority());
            assertThat(relayed.properties().getMessageId()).isEqualTo(direct.properties().getMessageId());
            assertThat(relayed.properties().getType()).isEqualTo(direct.properties().getType());
            assertThat(relayed.properties().getCorrelationId()).isEqualTo(direct.properties().getCorrelationId());
            assertThat(relayed.properties().getTimestamp()).isEqualTo(direct.properties().getTimestamp());
            assertThat(relayed.properties().getHeaders()).isEqualTo(direct.properties().getHeaders());
            assertThat(relayed.properties().getHeaders())
                    .containsKey(DomainEventSigner.SIGNATURE_HEADER)
                    .containsEntry(DomainEventSigner.SIGNED_AT_HEADER, Long.toString(SIGNED_AT.toEpochMilli()));
        }

        @Test
        @DisplayName("Signs a relayed message for the destination it is relayed to")
        void relayedSignatureVerifies() throws Exception {
            StringValue event = StringValue.of("member-42");
            DomainEventSigner signer = new DomainEventSigner("relay-identity-secret");

            relay(event);
            Published relayed = published(1).get(0);

            Map<String, Object> headers = relayed.properties().getHeaders();
            assertThat(signer.matches(EXCHANGE, ROUTING_KEY,
                    String.valueOf(headers.get(DomainEventSigner.SIGNED_AT_HEADER)), relayed.body(),
                    String.valueOf(headers.get(DomainEventSigner.SIGNATURE_HEADER)))).isTrue();
            assertThat(signer.matches(EXCHANGE, "church.member.removed-from-church.v1",
                    String.valueOf(headers.get(DomainEventSigner.SIGNED_AT_HEADER)), relayed.body(),
                    String.valueOf(headers.get(DomainEventSigner.SIGNATURE_HEADER)))).isFalse();
        }
    }
}
