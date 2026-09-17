package com.ecclesiaflow.platform.events.signing;

import com.ecclesiaflow.platform.events.signing.DomainEventVerifier.Decision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class DomainEventVerifierTest {

    private static final String SECRET = "shared-hmac-secret-for-verifier-tests";
    private static final String EXCHANGE = "ecclesiaflow.domain-events";
    private static final String ROUTING_KEY = "member.profile.changed.v1";
    private static final Instant NOW = Instant.parse("2026-09-17T09:00:00Z");
    private static final String SIGNED_AT = Long.toString(NOW.toEpochMilli());

    private final DomainEventSigner signer = new DomainEventSigner(SECRET);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private static byte[] body() {
        return "event-body".getBytes(StandardCharsets.UTF_8);
    }

    private String validSignature() {
        return signer.sign(EXCHANGE, ROUTING_KEY, NOW.toEpochMilli(), body());
    }

    private DomainEventVerifier verifier(boolean strict) {
        return new DomainEventVerifier(signer, strict, Duration.ofMinutes(5), clock);
    }

    @Nested
    @DisplayName("signing disabled (blank secret) — always accept")
    class Disabled {
        private final DomainEventVerifier verifier =
                new DomainEventVerifier(new DomainEventSigner(""), true);

        @Test
        void acceptsEvenWithNoSignatureAndStrictMode() {
            // strict flag is irrelevant when the secret is blank — full escape hatch
            assertThat(verifier.verify(EXCHANGE, ROUTING_KEY, body(), null, null))
                    .isEqualTo(Decision.ACCEPT);
            assertThat(verifier.verify(EXCHANGE, ROUTING_KEY, body(), "garbage", "0"))
                    .isEqualTo(Decision.ACCEPT);
        }
    }

    @Nested
    @DisplayName("lenient mode (verify-signatures=false, default)")
    class Lenient {
        private final DomainEventVerifier verifier = verifier(false);

        @Test
        void validSignatureAccepted() {
            assertThat(verifier.verify(EXCHANGE, ROUTING_KEY, body(), validSignature(), SIGNED_AT))
                    .isEqualTo(Decision.ACCEPT);
        }

        @Test
        void missingSignatureAcceptedUnverified() {
            assertThat(verifier.verify(EXCHANGE, ROUTING_KEY, body(), null, SIGNED_AT))
                    .isEqualTo(Decision.ACCEPT_UNVERIFIED);
            assertThat(verifier.verify(EXCHANGE, ROUTING_KEY, body(), "  ", SIGNED_AT))
                    .isEqualTo(Decision.ACCEPT_UNVERIFIED);
        }

        @Test
        void invalidSignatureAcceptedUnverified() {
            assertThat(verifier.verify(EXCHANGE, ROUTING_KEY, body(), "AAAA", SIGNED_AT))
                    .isEqualTo(Decision.ACCEPT_UNVERIFIED);
        }

        @Test
        void tamperedBodyAcceptedUnverified() {
            byte[] tampered = "event-bodyX".getBytes(StandardCharsets.UTF_8);
            assertThat(verifier.verify(EXCHANGE, ROUTING_KEY, tampered, validSignature(), SIGNED_AT))
                    .isEqualTo(Decision.ACCEPT_UNVERIFIED);
        }

        @Test
        void replayOnAnotherRoutingKeyAcceptedUnverified() {
            assertThat(verifier.verify(EXCHANGE, "member.removed.v1", body(), validSignature(), SIGNED_AT))
                    .isEqualTo(Decision.ACCEPT_UNVERIFIED);
        }

        @Test
        void staleButGenuineSignatureAcceptedUnverified() {
            long old = NOW.minus(Duration.ofHours(1)).toEpochMilli();
            String sig = signer.sign(EXCHANGE, ROUTING_KEY, old, body());
            assertThat(verifier.verify(EXCHANGE, ROUTING_KEY, body(), sig, Long.toString(old)))
                    .isEqualTo(Decision.ACCEPT_UNVERIFIED);
        }
    }

    @Nested
    @DisplayName("strict mode (verify-signatures=true)")
    class Strict {
        private final DomainEventVerifier verifier = verifier(true);

        @Test
        void validSignatureAccepted() {
            assertThat(verifier.verify(EXCHANGE, ROUTING_KEY, body(), validSignature(), SIGNED_AT))
                    .isEqualTo(Decision.ACCEPT);
        }

        @Test
        void missingSignatureRejected() {
            assertThat(verifier.verify(EXCHANGE, ROUTING_KEY, body(), null, SIGNED_AT))
                    .isEqualTo(Decision.REJECT_MISSING);
        }

        @Test
        void invalidSignatureRejected() {
            assertThat(verifier.verify(EXCHANGE, ROUTING_KEY, body(), "AAAA", SIGNED_AT))
                    .isEqualTo(Decision.REJECT_INVALID);
        }

        @Test
        void tamperedBodyRejectedInvalid() {
            byte[] tampered = "event-bodyX".getBytes(StandardCharsets.UTF_8);
            assertThat(verifier.verify(EXCHANGE, ROUTING_KEY, tampered, validSignature(), SIGNED_AT))
                    .isEqualTo(Decision.REJECT_INVALID);
        }

        /**
         * F054, the whole point: a genuinely signed event re-published under a
         * sibling routing key. Before the destination was signed material this
         * returned ACCEPT.
         */
        @Test
        void genuineEventReplayedUnderAnotherRoutingKeyRejected() {
            assertThat(verifier.verify(EXCHANGE, "member.removed.v1", body(), validSignature(), SIGNED_AT))
                    .isEqualTo(Decision.REJECT_INVALID);
        }

        @Test
        void genuineEventReplayedOnAnotherExchangeRejected() {
            assertThat(verifier.verify("other.exchange", ROUTING_KEY, body(), validSignature(), SIGNED_AT))
                    .isEqualTo(Decision.REJECT_INVALID);
        }

        @Test
        void missingSignedAtRejectedAsInvalid() {
            // the instant is signed material, so its absence cannot match
            assertThat(verifier.verify(EXCHANGE, ROUTING_KEY, body(), validSignature(), null))
                    .isEqualTo(Decision.REJECT_INVALID);
        }
    }

    @Nested
    @DisplayName("freshness window")
    class Freshness {
        private final DomainEventVerifier verifier = verifier(true);

        private Decision at(Duration offset) {
            long when = NOW.plus(offset).toEpochMilli();
            String sig = signer.sign(EXCHANGE, ROUTING_KEY, when, body());
            return verifier.verify(EXCHANGE, ROUTING_KEY, body(), sig, Long.toString(when));
        }

        @Test
        void insideTheWindowIsAccepted() {
            assertThat(at(Duration.ZERO)).isEqualTo(Decision.ACCEPT);
            assertThat(at(Duration.ofMinutes(-4))).isEqualTo(Decision.ACCEPT);
            // symmetric: a publisher whose clock runs slightly ahead is not punished
            assertThat(at(Duration.ofMinutes(4))).isEqualTo(Decision.ACCEPT);
        }

        @Test
        void exactlyAtTheEdgeIsAccepted() {
            assertThat(at(Duration.ofMinutes(-5))).isEqualTo(Decision.ACCEPT);
        }

        @Test
        void olderThanTheWindowIsRejectedAsStale() {
            assertThat(at(Duration.ofMinutes(-6))).isEqualTo(Decision.REJECT_STALE);
        }

        @Test
        void farInTheFutureIsRejectedAsStale() {
            assertThat(at(Duration.ofHours(2))).isEqualTo(Decision.REJECT_STALE);
        }

        @Test
        void defaultWindowIsFiveMinutes() {
            assertThat(DomainEventVerifier.DEFAULT_FRESHNESS_WINDOW).isEqualTo(Duration.ofMinutes(5));
        }

        @Test
        void twoArgConstructorUsesTheDefaultWindow() {
            // the constructor every consumer actually calls must not be inert
            DomainEventVerifier plain = new DomainEventVerifier(signer, true);
            long old = Instant.now().minus(Duration.ofHours(1)).toEpochMilli();
            String sig = signer.sign(EXCHANGE, ROUTING_KEY, old, body());
            assertThat(plain.verify(EXCHANGE, ROUTING_KEY, body(), sig, Long.toString(old)))
                    .isEqualTo(Decision.REJECT_STALE);
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
            assertThat(Decision.REJECT_STALE.isAccepted()).isFalse();
        }
    }
}
