package com.ecclesiaflow.platform.events.outbox.amqp;

import com.ecclesiaflow.platform.events.outbox.OutboxMessage;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConverter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Maps an event to its stored form and back. A converter property the outbox does not store is
 * refused at staging, rather than silently missing from the relayed message.
 */
public class AmqpOutboxMessageMapper {

    private static final MessageProperties DEFAULTS = new MessageProperties();

    private final MessageConverter converter;

    public AmqpOutboxMessageMapper(MessageConverter converter) {
        this.converter = converter;
    }

    public OutboxMessage toOutboxMessage(String exchange, String routingKey, Object event) {
        Message message = converter.toMessage(event, new MessageProperties());
        MessageProperties properties = message.getMessageProperties();
        requireStoredPropertiesOnly(properties);
        return new OutboxMessage(exchange, routingKey, message.getBody(), properties.getContentType(),
                properties.getContentEncoding(), properties.getMessageId(), textHeaders(properties.getHeaders()));
    }

    public Message toAmqpMessage(OutboxMessage message) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(message.contentType());
        properties.setContentEncoding(message.contentEncoding());
        properties.setMessageId(message.messageId());
        message.headers().forEach(properties::setHeader);
        return new Message(message.payload(), properties);
    }

    private static void requireStoredPropertiesOnly(MessageProperties properties) {
        requireDefault("type", properties.getType(), DEFAULTS.getType());
        requireDefault("correlationId", properties.getCorrelationId(), DEFAULTS.getCorrelationId());
        requireDefault("replyTo", properties.getReplyTo(), DEFAULTS.getReplyTo());
        requireDefault("expiration", properties.getExpiration(), DEFAULTS.getExpiration());
        requireDefault("timestamp", properties.getTimestamp(), DEFAULTS.getTimestamp());
        requireDefault("userId", properties.getUserId(), DEFAULTS.getUserId());
        requireDefault("appId", properties.getAppId(), DEFAULTS.getAppId());
        requireDefault("clusterId", properties.getClusterId(), DEFAULTS.getClusterId());
        requireDefault("priority", properties.getPriority(), DEFAULTS.getPriority());
        requireDefault("deliveryMode", properties.getDeliveryMode(), DEFAULTS.getDeliveryMode());
    }

    private static void requireDefault(String property, Object actual, Object expected) {
        if (!Objects.equals(actual, expected)) {
            throw new IllegalArgumentException("The message converter set the AMQP property '" + property
                    + "', which the outbox does not store; the relayed message would differ from a direct publish");
        }
    }

    private static Map<String, String> textHeaders(Map<String, Object> headers) {
        Map<String, String> text = new LinkedHashMap<>();
        headers.forEach((name, value) -> {
            if (!(value instanceof String string)) {
                throw new IllegalArgumentException("Header '" + name + "' is not text ("
                        + (value == null ? "null" : value.getClass().getSimpleName())
                        + "); the outbox stores text headers only");
            }
            text.put(name, string);
        });
        return text;
    }
}
