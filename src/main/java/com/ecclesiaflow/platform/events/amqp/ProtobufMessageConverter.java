package com.ecclesiaflow.platform.events.amqp;

import com.google.protobuf.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.amqp.support.converter.MessageConverter;

import java.util.Objects;

/**
 * Wire format of every protobuf message the platform queues: the serialized body,
 * {@code application/x-protobuf}, and the concrete class name in a {@code __TypeId__} header.
 *
 * <p>Only {@code com.ecclesiaflow.*} protobuf types are loaded, and checked before initialization.
 * Every refusal is a {@link MessageConversionException}, which a listener container rejects without
 * requeue, so the message parks in its dead-letter queue.</p>
 *
 * <p>The church events share field numbers, so reading one as another parses without error and does
 * the opposite: a queue that carries more than one type must use the strict constructor.</p>
 */
public class ProtobufMessageConverter implements MessageConverter {

    static final String CONTENT_TYPE = "application/x-protobuf";
    static final String TYPE_ID_HEADER = "__TypeId__";
    private static final String TRUSTED_PACKAGE_PREFIX = "com.ecclesiaflow.";

    private final Class<? extends Message> defaultType;

    /** Refuses a message whose header is missing or names a class this service does not have. */
    public ProtobufMessageConverter() {
        this.defaultType = null;
    }

    /**
     * Reads a message whose header is missing, or names a class this service does not have, as
     * {@code defaultType}.
     */
    public ProtobufMessageConverter(Class<? extends Message> defaultType) {
        this.defaultType = Objects.requireNonNull(defaultType, "defaultType");
    }

    @Override
    public org.springframework.amqp.core.Message toMessage(Object object, MessageProperties messageProperties)
            throws MessageConversionException {
        if (!(object instanceof Message protoMessage)) {
            throw new MessageConversionException("Object must be a Protobuf Message");
        }
        messageProperties.setContentType(CONTENT_TYPE);
        messageProperties.setHeader(TYPE_ID_HEADER, object.getClass().getName());
        return new org.springframework.amqp.core.Message(protoMessage.toByteArray(), messageProperties);
    }

    @Override
    public Object fromMessage(org.springframework.amqp.core.Message message) throws MessageConversionException {
        Class<? extends Message> type = typeOf(message.getMessageProperties().getHeader(TYPE_ID_HEADER));
        try {
            return type.getMethod("parseFrom", byte[].class).invoke(null, (Object) message.getBody());
        } catch (ReflectiveOperationException e) {
            throw new MessageConversionException("Failed to convert Protobuf message", e);
        }
    }

    private Class<? extends Message> typeOf(Object typeId) {
        if (typeId == null) {
            return defaultOrRefuse("Message carries no " + TYPE_ID_HEADER + " header", null);
        }
        if (!(typeId instanceof String name)) {
            throw new MessageConversionException(TYPE_ID_HEADER + " header is not a class name");
        }
        if (!name.startsWith(TRUSTED_PACKAGE_PREFIX)) {
            throw new MessageConversionException(
                    "Untrusted message type: " + name + ". Only com.ecclesiaflow.* types are allowed.");
        }
        Class<?> type;
        try {
            // Not initialized: a static initializer must not run before the type is known to be a message.
            type = Class.forName(name, false, ProtobufMessageConverter.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            return defaultOrRefuse("Unknown message type: " + name, e);
        }
        if (!Message.class.isAssignableFrom(type)) {
            throw new MessageConversionException("Not a protobuf message type: " + name);
        }
        return type.asSubclass(Message.class);
    }

    private Class<? extends Message> defaultOrRefuse(String refusal, Throwable cause) {
        if (defaultType == null) {
            throw new MessageConversionException(refusal, cause);
        }
        return defaultType;
    }
}
