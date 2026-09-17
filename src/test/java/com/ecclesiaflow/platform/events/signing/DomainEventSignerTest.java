package com.ecclesiaflow.platform.events.signing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainEventSignerTest {

    private static final String SECRET = "super-secret-shared-hmac-key-32bytes!!";
    private static final String EXCHANGE = "ecclesiaflow.domain-events";
    private static final String ROUTING_KEY = "member.profile.changed.v1";
    private static final long SIGNED_AT = 1_789_000_000_000L;

    private final DomainEventSigner signer = new DomainEventSigner(SECRET);

    private static byte[] body() {
        return "protobuf-wire-bytes".getBytes(StandardCharsets.UTF_8);
    }

    private String sign() {
        return signer.sign(EXCHANGE, ROUTING_KEY, SIGNED_AT, body());
    }

    private boolean matches(String exchange, String routingKey, String signedAt, byte[] body, String candidate) {
        return signer.matches(exchange, routingKey, signedAt, body, candidate);
    }

    @Nested
    @DisplayName("isEnabled")
    class IsEnabled {
        @Test
        void trueWhenSecretPresent() {
            assertThat(signer.isEnabled()).isTrue();
        }

        @Test
        void falseWhenSecretNull() {
            assertThat(new DomainEventSigner(null).isEnabled()).isFalse();
        }

        @Test
        void falseWhenSecretBlank() {
            assertThat(new DomainEventSigner("   ").isEnabled()).isFalse();
            assertThat(new DomainEventSigner("").isEnabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("sign + matches round-trip")
    class RoundTrip {
        @Test
        void signThenMatchesSucceeds() {
            String sig = sign();
            assertThat(sig).isNotBlank();
            assertThat(matches(EXCHANGE, ROUTING_KEY, Long.toString(SIGNED_AT), body(), sig)).isTrue();
        }

        @Test
        void signatureIsDeterministicAndBase64() {
            assertThat(sign()).isEqualTo(sign());
            // valid Base64 → decodes; HMAC-SHA256 output is 32 bytes
            assertThat(Base64.getDecoder().decode(sign())).hasSize(32);
        }
    }

    @Nested
    @DisplayName("the signature is bound to its destination, not only to the body")
    class DestinationBinding {

        /**
         * The finding itself (F054). The old signer signed the body alone, so a
         * legitimately signed « profile changed » could be re-published under the
         * « member removed » routing key and still verify — one serializer, one
         * body shape, a different meaning at the other end.
         */
        @Test
        void sameBodyUnderAnotherRoutingKeyDoesNotVerify() {
            String sig = sign();
            assertThat(matches(EXCHANGE, "member.removed.v1", Long.toString(SIGNED_AT), body(), sig))
                    .isFalse();
        }

        @Test
        void sameBodyOnAnotherExchangeDoesNotVerify() {
            String sig = sign();
            assertThat(matches("other.exchange", ROUTING_KEY, Long.toString(SIGNED_AT), body(), sig))
                    .isFalse();
        }

        @Test
        void sameBodySignedAtAnotherInstantDoesNotVerify() {
            String sig = sign();
            assertThat(matches(EXCHANGE, ROUTING_KEY, Long.toString(SIGNED_AT + 1), body(), sig))
                    .isFalse();
        }

        /**
         * No choice of exchange and routing key may be made to look like another
         * pair. The NUL separator is what guarantees it — UTF-8 cannot produce
         * that byte, so the fields cannot bleed into each other.
         */
        @Test
        void fieldBoundariesCannotBeShiftedBetweenExchangeAndRoutingKey() {
            String a = signer.sign("ecclesiaflow.domain", "events.member.v1", SIGNED_AT, body());
            String b = signer.sign("ecclesiaflow.domain.events", "member.v1", SIGNED_AT, body());
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        void nullDestinationIsTreatedAsEmptyAndRoundTrips() {
            // AMQP reports the default exchange as "", and a null must not blow up
            // on either side or the two sides would disagree.
            String sig = signer.sign(null, ROUTING_KEY, SIGNED_AT, body());
            assertThat(matches("", ROUTING_KEY, Long.toString(SIGNED_AT), body(), sig)).isTrue();
        }
    }

    @Nested
    @DisplayName("tamper detection")
    class Tamper {
        @Test
        void tamperedBodyFails() {
            String sig = sign();
            byte[] tampered = "protobuf-wire-bytez".getBytes(StandardCharsets.UTF_8);
            assertThat(matches(EXCHANGE, ROUTING_KEY, Long.toString(SIGNED_AT), tampered, sig)).isFalse();
        }

        @Test
        void differentSecretFails() {
            String sig = new DomainEventSigner("a-different-secret")
                    .sign(EXCHANGE, ROUTING_KEY, SIGNED_AT, body());
            assertThat(matches(EXCHANGE, ROUTING_KEY, Long.toString(SIGNED_AT), body(), sig)).isFalse();
        }

        @Test
        void tamperedSignatureFails() {
            String sig = sign();
            String flipped = sig.charAt(0) == 'A' ? "B" + sig.substring(1) : "A" + sig.substring(1);
            assertThat(matches(EXCHANGE, ROUTING_KEY, Long.toString(SIGNED_AT), body(), flipped)).isFalse();
        }
    }

    @Nested
    @DisplayName("matches edge cases")
    class MatchesEdges {
        @Test
        void nullOrBlankCandidateFails() {
            assertThat(matches(EXCHANGE, ROUTING_KEY, "1", body(), null)).isFalse();
            assertThat(matches(EXCHANGE, ROUTING_KEY, "1", body(), "")).isFalse();
            assertThat(matches(EXCHANGE, ROUTING_KEY, "1", body(), "   ")).isFalse();
        }

        @Test
        void nullBodyFails() {
            assertThat(matches(EXCHANGE, ROUTING_KEY, Long.toString(SIGNED_AT), null, sign())).isFalse();
        }

        @Test
        void nullSignedAtFails() {
            // a message with no x-ef-signed-at header cannot have been signed by us
            assertThat(matches(EXCHANGE, ROUTING_KEY, null, body(), sign())).isFalse();
        }

        @Test
        void malformedBase64CandidateFails() {
            assertThat(matches(EXCHANGE, ROUTING_KEY, "1", body(), "not-valid-base64!!@@")).isFalse();
        }

        @Test
        void wrongLengthCandidateFails() {
            // valid Base64 but not 32 bytes → constant-time length check returns false
            String shortSig = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});
            assertThat(matches(EXCHANGE, ROUTING_KEY, "1", body(), shortSig)).isFalse();
        }

        @Test
        void disabledSignerNeverMatches() {
            DomainEventSigner disabled = new DomainEventSigner("");
            assertThat(disabled.matches(EXCHANGE, ROUTING_KEY, "1", body(), "anything")).isFalse();
        }
    }

    @Nested
    @DisplayName("sign guards")
    class SignGuards {
        @Test
        void signOnDisabledSignerThrows() {
            DomainEventSigner disabled = new DomainEventSigner(null);
            assertThatThrownBy(() -> disabled.sign(EXCHANGE, ROUTING_KEY, SIGNED_AT, body()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("disabled");
        }

        @Test
        void signNullBodyThrows() {
            assertThatThrownBy(() -> signer.sign(EXCHANGE, ROUTING_KEY, SIGNED_AT, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("canonical form")
    class Canonical {
        @Test
        void isExchangeRoutingKeyInstantThenBodySeparatedByNul() {
            byte[] canonical = DomainEventSigner.canonical("ex", "rk", "7", new byte[]{9, 9});
            assertThat(canonical).isEqualTo(new byte[]{
                    'e', 'x', 0,
                    'r', 'k', 0,
                    '7', 0,
                    9, 9});
        }
    }

    @Test
    void headerConstantsAreStable() {
        // wire contract: these names must never change without a coordinated migration
        assertThat(DomainEventSigner.SIGNATURE_HEADER).isEqualTo("x-ef-signature");
        assertThat(DomainEventSigner.SIGNED_AT_HEADER).isEqualTo("x-ef-signed-at");
    }
}
