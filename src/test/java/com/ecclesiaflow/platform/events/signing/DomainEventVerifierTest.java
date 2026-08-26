package com.ecclesiaflow.platform.events.signing;

import com.ecclesiaflow.platform.events.signing.DomainEventVerifier.Decision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DomainEventVerifierTest {

    private static final String SECRET = "shared-hmac-secret-for-verifier-tests";

    private final DomainEventSigner signer = new DomainEventSigner(SECRET);

    private static byte[] body() {
        return "event-body".getBytes(StandardCharsets.UTF_8);
    }

    private String validSignature() {
        return signer.sign(body());
    }

    @Nested
    @DisplayName("signing disabled (blank secret) — always accept")
    class Disabled {
        private final DomainEventVerifier verifier =
                new DomainEventVerifier(new DomainEventSigner(""), true);

        @Test
        void acceptsEvenWithNoSignatureAndStrictMode() {
            // strict flag is irrelevant when the secret is blank — full escape hatch
            assertThat(verifier.verify(body(), null)).isEqualTo(Decision.ACCEPT);
            assertThat(verifier.verify(body(), "garbage")).isEqualTo(Decision.ACCEPT);
        }
    }

    @Nested
    @DisplayName("lenient mode (verify-signatures=false, default)")
    class Lenient {
        private final DomainEventVerifier verifier = new DomainEventVerifier(signer, false);

        @Test
        void validSignatureAccepted() {
            assertThat(verifier.verify(body(), validSignature())).isEqualTo(Decision.ACCEPT);
        }

        @Test
        void missingSignatureAcceptedUnverified() {
            assertThat(verifier.verify(body(), null)).isEqualTo(Decision.ACCEPT_UNVERIFIED);
            assertThat(verifier.verify(body(), "  ")).isEqualTo(Decision.ACCEPT_UNVERIFIED);
        }

        @Test
        void invalidSignatureAcceptedUnverified() {
            assertThat(verifier.verify(body(), "AAAA")).isEqualTo(Decision.ACCEPT_UNVERIFIED);
        }

        @Test
        void tamperedBodyAcceptedUnverified() {
            String sig = validSignature();
            byte[] tampered = "event-bodyX".getBytes(StandardCharsets.UTF_8);
            assertThat(verifier.verify(tampered, sig)).isEqualTo(Decision.ACCEPT_UNVERIFIED);
        }
    }

    @Nested
    @DisplayName("strict mode (verify-signatures=true)")
    class Strict {
        private final DomainEventVerifier verifier = new DomainEventVerifier(signer, true);

        @Test
        void validSignatureAccepted() {
            assertThat(verifier.verify(body(), validSignature())).isEqualTo(Decision.ACCEPT);
        }

        @Test
        void missingSignatureRejected() {
            assertThat(verifier.verify(body(), null)).isEqualTo(Decision.REJECT_MISSING);
        }

        @Test
        void invalidSignatureRejected() {
            assertThat(verifier.verify(body(), "AAAA")).isEqualTo(Decision.REJECT_INVALID);
        }

        @Test
        void tamperedBodyRejectedInvalid() {
            String sig = validSignature();
            byte[] tampered = "event-bodyX".getBytes(StandardCharsets.UTF_8);
            assertThat(verifier.verify(tampered, sig)).isEqualTo(Decision.REJECT_INVALID);
        }
    }

    @Nested
    @DisplayName("Decision.isAccepted")
    class DecisionFlag {
        @Test
        void acceptingDecisionsAreAccepted() {
            assertThat(Decision.ACCEPT.isAccepted()).isTrue();
            assertThat(Decision.ACCEPT_UNVERIFIED.isAccepted()).isTrue();
        }

        @Test
        void rejectingDecisionsAreNotAccepted() {
            assertThat(Decision.REJECT_MISSING.isAccepted()).isFalse();
            assertThat(Decision.REJECT_INVALID.isAccepted()).isFalse();
        }
    }
}
