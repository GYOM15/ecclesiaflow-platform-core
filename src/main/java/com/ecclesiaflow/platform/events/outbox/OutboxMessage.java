package com.ecclesiaflow.platform.events.outbox;

import com.ecclesiaflow.platform.events.signing.DomainEventSigner;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * A message as stored in the outbox. The signing headers are refused: the relay stamps them
 * when it publishes, and a stored signature would be stale by then.
 */
public record OutboxMessage(String exchange, String routingKey, byte[] payload, String contentType,
                            String contentEncoding, String messageId, Map<String, String> headers) {

    public OutboxMessage {
        requireDestination("exchange", exchange);
        requireDestination("routingKey", routingKey);
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        payload = payload.clone();
        headers = copyHeaders(headers);
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    // The signature binds exchange and routing key with NUL separators, so a NUL inside
    // either would let two destinations share one signature.
    private static void requireDestination(String name, String value) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        if (value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException(name + " must not contain a NUL character");
        }
    }

    private static Map<String, String> copyHeaders(Map<String, String> headers) {
        if (headers == null) {
            return Map.of();
        }
        headers.forEach((name, value) -> {
            if (value == null) {
                throw new IllegalArgumentException("header '" + name + "' has no value");
            }
            if (DomainEventSigner.SIGNATURE_HEADER.equals(name) || DomainEventSigner.SIGNED_AT_HEADER.equals(name)) {
                throw new IllegalArgumentException("header '" + name + "' is stamped at relay time and cannot be stored");
            }
        });
        return Map.copyOf(headers);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof OutboxMessage that
                && exchange.equals(that.exchange)
                && routingKey.equals(that.routingKey)
                && Arrays.equals(payload, that.payload)
                && Objects.equals(contentType, that.contentType)
                && Objects.equals(contentEncoding, that.contentEncoding)
                && Objects.equals(messageId, that.messageId)
                && headers.equals(that.headers);
    }

    @Override
    public int hashCode() {
        return Objects.hash(exchange, routingKey, Arrays.hashCode(payload), contentType, contentEncoding,
                messageId, headers);
    }

    // The payload may carry personal data; only its size is printed.
    @Override
    public String toString() {
        return "OutboxMessage[exchange=" + exchange + ", routingKey=" + routingKey + ", " + payload.length
                + " bytes, contentType=" + contentType + ", headers=" + headers.keySet() + "]";
    }
}
