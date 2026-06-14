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

    private final DomainEventSigner signer = new DomainEventSigner(SECRET);

    private static byte[] body() {
        return "protobuf-wire-bytes".getBytes(StandardCharsets.UTF_8);
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
            String sig = signer.sign(body());
            assertThat(sig).isNotBlank();
            assertThat(signer.matches(body(), sig)).isTrue();
        }

        @Test
        void signatureIsDeterministicAndBase64() {
            String a = signer.sign(body());
            String b = signer.sign(body());
            assertThat(a).isEqualTo(b);
            // valid Base64 → decodes; HMAC-SHA256 output is 32 bytes
            assertThat(Base64.getDecoder().decode(a)).hasSize(32);
        }
    }

    @Nested
    @DisplayName("tamper detection")
    class Tamper {
        @Test
        void tamperedBodyFails() {
            String sig = signer.sign(body());
            byte[] tampered = "protobuf-wire-bytez".getBytes(StandardCharsets.UTF_8);
            assertThat(signer.matches(tampered, sig)).isFalse();
        }

        @Test
        void differentSecretFails() {
            String sig = new DomainEventSigner("a-different-secret").sign(body());
            assertThat(signer.matches(body(), sig)).isFalse();
        }

        @Test
        void tamperedSignatureFails() {
            String sig = signer.sign(body());
            String flipped = sig.charAt(0) == 'A' ? "B" + sig.substring(1) : "A" + sig.substring(1);
            assertThat(signer.matches(body(), flipped)).isFalse();
        }
    }

    @Nested
    @DisplayName("matches edge cases")
    class MatchesEdges {
        @Test
        void nullOrBlankCandidateFails() {
            assertThat(signer.matches(body(), null)).isFalse();
            assertThat(signer.matches(body(), "")).isFalse();
            assertThat(signer.matches(body(), "   ")).isFalse();
        }

        @Test
        void nullBodyFails() {
            String sig = signer.sign(body());
            assertThat(signer.matches(null, sig)).isFalse();
        }

        @Test
        void malformedBase64CandidateFails() {
            assertThat(signer.matches(body(), "not-valid-base64!!@@")).isFalse();
        }

        @Test
        void wrongLengthCandidateFails() {
            // valid Base64 but not 32 bytes → constant-time length check returns false
            String shortSig = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});
            assertThat(signer.matches(body(), shortSig)).isFalse();
        }

        @Test
        void disabledSignerNeverMatches() {
            DomainEventSigner disabled = new DomainEventSigner("");
            // even a value that would be "correct" cannot match when disabled
            assertThat(disabled.matches(body(), "anything")).isFalse();
        }
    }

    @Nested
    @DisplayName("sign guards")
    class SignGuards {
        @Test
        void signOnDisabledSignerThrows() {
            DomainEventSigner disabled = new DomainEventSigner(null);
            assertThatThrownBy(() -> disabled.sign(body()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("disabled");
        }

        @Test
        void signNullBodyThrows() {
            assertThatThrownBy(() -> signer.sign(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void signatureHeaderConstantIsStable() {
        // wire contract: the header name must never change without a coordinated migration
        assertThat(DomainEventSigner.SIGNATURE_HEADER).isEqualTo("x-ef-signature");
    }
}
