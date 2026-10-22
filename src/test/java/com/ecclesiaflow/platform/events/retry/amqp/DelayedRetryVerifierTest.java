package com.ecclesiaflow.platform.events.retry.amqp;

import com.ecclesiaflow.platform.events.retry.DelayedRetryStamp;
import com.ecclesiaflow.platform.events.retry.DelayedRetryTopology;
import com.ecclesiaflow.platform.events.retry.RetryHop;
import com.ecclesiaflow.platform.events.signing.DomainEventSigner;
import com.ecclesiaflow.platform.events.signing.DomainEventVerifier;
import com.ecclesiaflow.platform.events.signing.DomainEventVerifier.Decision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Design point 1 of rank 3: a delayed retry keeps the HMAC check, its freshness moves to the stamp. */
class DelayedRetryVerifierTest {

    private static final String SECRET = "retry-verifier-secret";
    private static final String QUEUE = "auth.subscriber.member-account-status";
    private static final String EXCHANGE = "ecclesiaflow.domain-events";
    private static final String ROUTING_KEY = "members.account.deactivated";
    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");
    private static final Duration WINDOW = DomainEventVerifier.DEFAULT_FRESHNESS_WINDOW;
    private static final byte[] BODY = "deactivated".getBytes(StandardCharsets.UTF_8);

    private final DomainEventSigner signer = new DomainEventSigner(SECRET);
    private final DelayedRetryStamp stamp = new DelayedRetryStamp(SECRET);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final DelayedRetryTopologies topologies = new DelayedRetryTopologies(List.of(new DelayedRetryTopology(QUEUE)));

    private DelayedRetryVerifier verifier(boolean strict) {
        return new DelayedRetryVerifier(signer, stamp, topologies, strict, WINDOW, clock);
    }

    private Decision verify(Message message) {
        return verifier(true).verifyRedelivery(message).orElseThrow();
    }

    @Nested
    @DisplayName("A genuine redelivery")
    class Genuine {

        @Test
        @DisplayName("from the 2 h stage is accepted, where the first-delivery rule would call it stale")
        void longestStageIsAccepted() {
            Message message = redelivery(Duration.ofMinutes(160), hop(4, Duration.ofMinutes(121)));
            DomainEventVerifier firstDelivery = new DomainEventVerifier(signer, true, WINDOW, clock);
            MessageProperties properties = message.getMessageProperties();

            assertThat(verify(message)).isEqualTo(Decision.ACCEPT);
            assertThat(firstDelivery.verify(EXCHANGE, ROUTING_KEY, BODY,
                    (String) properties.getHeader(DomainEventSigner.SIGNATURE_HEADER),
                    (String) properties.getHeader(DomainEventSigner.SIGNED_AT_HEADER)))
                    .isEqualTo(Decision.REJECT_STALE);
        }

        @Test
        @DisplayName("from the 1 min stage is accepted")
        void firstStageIsAccepted() {
            assertThat(verify(redelivery(Duration.ofSeconds(70), hop(1, Duration.ofMinutes(1)))))
                    .isEqualTo(Decision.ACCEPT);
        }

        @Test
        @DisplayName("is accepted up to its stage's delay plus the margin, 2 h 10 min for the last stage")
        void acceptedUpToDelayPlusMargin() {
            assertThat(verify(redelivery(Duration.ofMinutes(170), hop(4, Duration.ofMinutes(130)))))
                    .isEqualTo(Decision.ACCEPT);
            assertThat(verify(redelivery(Duration.ofMinutes(171), hop(4, Duration.ofMinutes(131)))))
                    .isEqualTo(Decision.REJECT_STALE);
        }

        @Test
        @DisplayName("waiting longer than its own stage allows is stale, even below 2 h 10 min")
        void boundedByItsOwnStage() {
            assertThat(verify(redelivery(Duration.ofMinutes(13), hop(1, Duration.ofMinutes(12)))))
                    .isEqualTo(Decision.REJECT_STALE);
        }
    }

    @Nested
    @DisplayName("Freshness rests on the stamp, never on x-death")
    class Freshness {

        @Test
        @DisplayName("a forged recent x-death does not rescue a hop that waited too long")
        void forgedXDeathIsIgnored() {
            Message message = redelivery(Duration.ofHours(5), hop(2, Duration.ofHours(4)));
            message.getMessageProperties().setHeader("x-death", List.of(Map.of(
                    "queue", QUEUE + ".retry.5m", "reason", "expired", "count", 1L,
                    "time", java.util.Date.from(NOW.minusSeconds(10)))));

            assertThat(verify(message)).isEqualTo(Decision.REJECT_STALE);
        }

        @Test
        @DisplayName("a hop stamped in the future beyond the clock-skew window is stale")
        void hopFromTheFuture() {
            assertThat(verify(redelivery(Duration.ofMinutes(2), hop(1, Duration.ofMinutes(-6)))))
                    .isEqualTo(Decision.REJECT_STALE);
            assertThat(verify(redelivery(Duration.ofMinutes(2), hop(1, Duration.ofMinutes(-4)))))
                    .isEqualTo(Decision.ACCEPT);
        }

        @Test
        @DisplayName("a signature days old passes under a fresh stamp: a secret holder re-admitted the copy")
        void signatureAgeIsNotBounded() {
            assertThat(verify(redelivery(Duration.ofDays(3), hop(1, Duration.ofMinutes(1)))))
                    .isEqualTo(Decision.ACCEPT);
        }
    }

    @Nested
    @DisplayName("A forged or altered redelivery is invalid")
    class Forged {

        @Test
        @DisplayName("hop headers without a stamp, as a publisher without the secret could write them")
        void hopWithoutStamp() {
            Message message = redelivery(Duration.ofMinutes(2), hop(1, Duration.ofMinutes(1)));
            message.getMessageProperties().getHeaders().remove(RetryHop.STAMP_HEADER);

            assertThat(verify(message)).isEqualTo(Decision.REJECT_INVALID);
        }

        @Test
        @DisplayName("a stage changed after stamping, to stretch the window")
        void stageChanged() {
            Message message = redelivery(Duration.ofMinutes(40), hop(1, Duration.ofMinutes(35)));
            message.getMessageProperties().setHeader(RetryHop.ATTEMPT_HEADER, "3");

            assertThat(verify(message)).isEqualTo(Decision.REJECT_INVALID);
        }

        @Test
        @DisplayName("an instant changed after stamping")
        void instantChanged() {
            Message message = redelivery(Duration.ofHours(3), hop(1, Duration.ofHours(3)));
            message.getMessageProperties().setHeader(RetryHop.RETRIED_AT_HEADER,
                    Long.toString(NOW.minusSeconds(60).toEpochMilli()));

            assertThat(verify(message)).isEqualTo(Decision.REJECT_INVALID);
        }

        @Test
        @DisplayName("a body changed after signing, even under a freshly minted stamp")
        void bodyChanged() {
            RetryHop hop = hop(1, Duration.ofMinutes(1));
            Message message = redelivery(Duration.ofMinutes(2), hop);
            byte[] forged = "reactivated".getBytes(StandardCharsets.UTF_8);
            MessageProperties properties = message.getMessageProperties();
            properties.setHeader(RetryHop.STAMP_HEADER, stamp.sign(hop,
                    (String) properties.getHeader(DomainEventSigner.SIGNED_AT_HEADER),
                    (String) properties.getHeader(DomainEventSigner.SIGNATURE_HEADER), forged));

            assertThat(verify(new Message(forged, properties))).isEqualTo(Decision.REJECT_INVALID);
        }

        @Test
        @DisplayName("an original route the event was not signed for")
        void routeChanged() {
            RetryHop rerouted = new RetryHop(QUEUE, 1, NOW.minusSeconds(60).toEpochMilli(), EXCHANGE,
                    "members.account.reactivated");
            Message message = redelivery(Duration.ofMinutes(2), rerouted);

            assertThat(verify(message)).isEqualTo(Decision.REJECT_INVALID);
        }

        @Test
        @DisplayName("a stage the schedule does not have")
        void stageOutsideTheSchedule() {
            assertThat(verify(redelivery(Duration.ofMinutes(2), hop(5, Duration.ofMinutes(1)))))
                    .isEqualTo(Decision.REJECT_INVALID);
        }

        @Test
        @DisplayName("no event signature, or one of an unknown format version")
        void missingOrUnsupportedSignature() {
            Message unsigned = redelivery(Duration.ofMinutes(2), hop(1, Duration.ofMinutes(1)));
            unsigned.getMessageProperties().getHeaders().remove(DomainEventSigner.SIGNATURE_HEADER);
            Message future = redelivery(Duration.ofMinutes(2), hop(1, Duration.ofMinutes(1)));
            future.getMessageProperties().setHeader(DomainEventSigner.SIGNATURE_VERSION_HEADER, "2");

            assertThat(verify(unsigned)).isEqualTo(Decision.REJECT_MISSING);
            assertThat(verify(future)).isEqualTo(Decision.REJECT_UNSUPPORTED_VERSION);
        }
    }

    @Test
    @DisplayName("Lenient mode delivers every refusal unverified")
    void lenientModeAcceptsUnverified() {
        Message stale = redelivery(Duration.ofHours(5), hop(1, Duration.ofHours(4)));
        Message forged = redelivery(Duration.ofMinutes(2), hop(1, Duration.ofMinutes(1)));
        forged.getMessageProperties().getHeaders().remove(RetryHop.STAMP_HEADER);
        Message unsigned = redelivery(Duration.ofMinutes(2), hop(1, Duration.ofMinutes(1)));
        unsigned.getMessageProperties().getHeaders().remove(DomainEventSigner.SIGNATURE_HEADER);

        assertThat(verifier(false).verifyRedelivery(stale)).contains(Decision.ACCEPT_UNVERIFIED);
        assertThat(verifier(false).verifyRedelivery(forged)).contains(Decision.ACCEPT_UNVERIFIED);
        assertThat(verifier(false).verifyRedelivery(unsigned)).contains(Decision.ACCEPT_UNVERIFIED);
    }

    @Test
    @DisplayName("Without a secret every redelivery is accepted, as every first delivery is")
    void disabledSigningAcceptsEverything() {
        DelayedRetryVerifier unsigned = new DelayedRetryVerifier(new DomainEventSigner(""), new DelayedRetryStamp(""),
                topologies, true, WINDOW, clock);
        Message message = redelivery(Duration.ofHours(5), hop(1, Duration.ofHours(4)));

        assertThat(unsigned.verifyRedelivery(message)).contains(Decision.ACCEPT);
    }

    @Test
    @DisplayName("Leaves any other delivery to the first-delivery rules")
    void leavesFirstDeliveriesAlone() {
        Message message = redelivery(Duration.ofMinutes(2), hop(1, Duration.ofMinutes(1)));
        message.getMessageProperties().setReceivedExchange(EXCHANGE);
        message.getMessageProperties().setReceivedRoutingKey(ROUTING_KEY);

        assertThat(verifier(true).verifyRedelivery(message)).isEmpty();
    }

    private static RetryHop hop(int attempt, Duration enteredStageAgo) {
        return new RetryHop(QUEUE, attempt, NOW.minus(enteredStageAgo).toEpochMilli(), EXCHANGE, ROUTING_KEY);
    }

    /** A copy back from a stage, as the recoverer wrote it, of an event signed {@code signedAgo} ago. */
    private Message redelivery(Duration signedAgo, RetryHop hop) {
        long signedAt = NOW.minus(signedAgo).toEpochMilli();
        String signature = signer.sign(EXCHANGE, ROUTING_KEY, signedAt, BODY);
        MessageProperties properties = new MessageProperties();
        properties.setConsumerQueue(QUEUE);
        properties.setReceivedExchange("");
        properties.setReceivedRoutingKey(QUEUE);
        properties.setHeader(DomainEventSigner.SIGNATURE_HEADER, signature);
        properties.setHeader(DomainEventSigner.SIGNED_AT_HEADER, Long.toString(signedAt));
        properties.setHeader(DomainEventSigner.SIGNATURE_VERSION_HEADER, DomainEventSigner.SIGNATURE_VERSION);
        hop.headers().forEach(properties::setHeader);
        properties.setHeader(RetryHop.STAMP_HEADER, stamp.sign(hop, Long.toString(signedAt), signature, BODY));
        return new Message(BODY, properties);
    }
}
