package com.ecclesiaflow.platform.events.outbox;

import com.ecclesiaflow.platform.events.signing.DomainEventSigner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxMessageTest {

    private static final byte[] BODY = "payload".getBytes(StandardCharsets.UTF_8);

    private static OutboxMessage message(Map<String, String> headers) {
        return new OutboxMessage("ecclesiaflow.domain-events", "church.member.removed.v1", BODY,
                "application/x-protobuf", null, null, headers);
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("Accepts the default exchange, written as an empty name")
        void acceptsTheDefaultExchange() {
            OutboxMessage message = new OutboxMessage("", "queue-name", BODY, null, null, null, Map.of());

            assertThat(message.exchange()).isEmpty();
        }

        @Test
        @DisplayName("Rejects a missing exchange, routing key or payload")
        void rejectsMissingParts() {
            assertThatThrownBy(() -> new OutboxMessage(null, "rk", BODY, null, null, null, Map.of()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exchange");
            assertThatThrownBy(() -> new OutboxMessage("ex", null, BODY, null, null, null, Map.of()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("routingKey");
            assertThatThrownBy(() -> new OutboxMessage("ex", "rk", null, null, null, null, Map.of()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("payload");
        }

        @Test
        @DisplayName("Rejects a NUL character in the destination, which the signature cannot bind")
        void rejectsNulInDestination() {
            assertThatThrownBy(() -> new OutboxMessage("ex\u0000x", "rk", BODY, null, null, null, Map.of()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exchange");
            assertThatThrownBy(() -> new OutboxMessage("ex", "r\u0000k", BODY, null, null, null, Map.of()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("routingKey");
        }

        @Test
        @DisplayName("Treats missing headers as no headers")
        void nullHeadersMeanNone() {
            assertThat(message(null).headers()).isEmpty();
        }

        @Test
        @DisplayName("Rejects a header without a value")
        void rejectsNullHeaderValue() {
            Map<String, String> headers = new HashMap<>();
            headers.put("__TypeId__", null);

            assertThatThrownBy(() -> message(headers))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("__TypeId__");
        }

        @Test
        @DisplayName("Rejects the signing headers: they are stamped at relay time, never stored")
        void rejectsSigningHeaders() {
            assertThatThrownBy(() -> message(Map.of(DomainEventSigner.SIGNATURE_HEADER, "forged")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(DomainEventSigner.SIGNATURE_HEADER);
            assertThatThrownBy(() -> message(Map.of(DomainEventSigner.SIGNED_AT_HEADER, "1")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(DomainEventSigner.SIGNED_AT_HEADER);
        }
    }

    @Nested
    @DisplayName("value semantics")
    class ValueSemantics {

        @Test
        @DisplayName("Copies the payload in and out, so a caller cannot rewrite a staged message")
        void payloadIsDefensivelyCopied() {
            byte[] body = "abc".getBytes(StandardCharsets.UTF_8);
            OutboxMessage message = new OutboxMessage("ex", "rk", body, null, null, null, Map.of());

            body[0] = 'z';
            message.payload()[1] = 'z';

            assertThat(message.payload()).isEqualTo("abc".getBytes(StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("Copies the headers, so a caller cannot rewrite a staged message")
        void headersAreDefensivelyCopied() {
            Map<String, String> headers = new HashMap<>(Map.of("__TypeId__", "a.B"));
            OutboxMessage message = message(headers);

            headers.put("__TypeId__", "c.D");

            assertThat(message.headers()).containsEntry("__TypeId__", "a.B");
            assertThatThrownBy(() -> message.headers().put("x", "y"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("Compares payloads by content")
        void equalityUsesPayloadContent() {
            OutboxMessage a = message(Map.of("__TypeId__", "a.B"));
            OutboxMessage b = message(Map.of("__TypeId__", "a.B"));
            OutboxMessage other = new OutboxMessage("ecclesiaflow.domain-events", "church.member.removed.v1",
                    "other".getBytes(StandardCharsets.UTF_8), "application/x-protobuf", null, null,
                    Map.of("__TypeId__", "a.B"));

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b).isNotEqualTo(other);
            assertThat(a).isNotEqualTo(null).isNotEqualTo("a string");
        }

        @Test
        @DisplayName("Tells apart messages that differ in any one part")
        void differsByEachPart() {
            Map<String, String> h = Map.of("__TypeId__", "a.B");
            OutboxMessage base = new OutboxMessage("ex", "rk", BODY, "ct", "enc", "id", h);

            assertThat(base)
                    .isNotEqualTo(new OutboxMessage("ex2", "rk", BODY, "ct", "enc", "id", h))
                    .isNotEqualTo(new OutboxMessage("ex", "rk2", BODY, "ct", "enc", "id", h))
                    .isNotEqualTo(new OutboxMessage("ex", "rk", BODY, "ct2", "enc", "id", h))
                    .isNotEqualTo(new OutboxMessage("ex", "rk", BODY, "ct", "enc2", "id", h))
                    .isNotEqualTo(new OutboxMessage("ex", "rk", BODY, "ct", "enc", "id2", h))
                    .isNotEqualTo(new OutboxMessage("ex", "rk", BODY, "ct", "enc", "id", Map.of()));
        }

        @Test
        @DisplayName("Keeps the payload out of toString, since it may carry personal data")
        void toStringHidesThePayload() {
            assertThat(message(Map.of()).toString())
                    .contains("church.member.removed.v1")
                    .contains("7 bytes")
                    .doesNotContain("payload=");
        }
    }
}
