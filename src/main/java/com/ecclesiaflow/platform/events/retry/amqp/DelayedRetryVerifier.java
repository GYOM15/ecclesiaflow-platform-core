package com.ecclesiaflow.platform.events.retry.amqp;

import com.ecclesiaflow.platform.events.retry.DelayedRetrySchedule;
import com.ecclesiaflow.platform.events.retry.DelayedRetryStamp;
import com.ecclesiaflow.platform.events.retry.RetryHop;
import com.ecclesiaflow.platform.events.signing.DomainEventSigner;
import com.ecclesiaflow.platform.events.signing.DomainEventVerifier;
import com.ecclesiaflow.platform.events.signing.DomainEventVerifier.Decision;
import com.ecclesiaflow.platform.events.signing.amqp.RedeliveryVerifier;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * Verifies a redelivery from a retry stage. The event's signature is still checked, against the route
 * the hop says it was first published on, and the stamp must vouch for the hop. Freshness moves from
 * the signature to the stamp: the redelivery is consumed within its stage's delay plus the schedule's
 * margin after it entered the stage, 2 h 10 min at most with the standard schedule. The signature's own
 * age is not bounded: only a holder of the events secret can stamp, and the stamp dates the copy it
 * made, which is also what lets an operator's replay re-admit a dead-lettered event.
 *
 * <p>Lenient mode turns every refusal into {@link Decision#ACCEPT_UNVERIFIED}, as for a first
 * delivery. Deduplication on {@code event_id} stays the consumer's, on every delivery.</p>
 */
public class DelayedRetryVerifier implements RedeliveryVerifier {

    private final DomainEventSigner signer;
    private final DelayedRetryStamp stamp;
    private final DelayedRetryTopologies topologies;
    private final boolean verifySignatures;
    private final Duration freshnessWindow;
    private final Clock clock;

    /** Strictness and the clock-skew window follow the module's first-delivery verifier. */
    public DelayedRetryVerifier(DomainEventSigner signer, DelayedRetryStamp stamp, DelayedRetryTopologies topologies,
                                boolean verifySignatures, Duration freshnessWindow, Clock clock) {
        this.signer = signer;
        this.stamp = stamp;
        this.topologies = topologies;
        this.verifySignatures = verifySignatures;
        this.freshnessWindow = freshnessWindow;
        this.clock = clock;
    }

    @Override
    public Optional<Decision> verifyRedelivery(Message message) {
        return topologies.claimedHop(message).map(hop -> verify(message, hop));
    }

    private Decision verify(Message message, RetryHop hop) {
        if (!signer.isEnabled()) {
            return Decision.ACCEPT;
        }
        MessageProperties properties = message.getMessageProperties();
        String signature = header(properties, DomainEventSigner.SIGNATURE_HEADER);
        String signedAt = header(properties, DomainEventSigner.SIGNED_AT_HEADER);
        String version = header(properties, DomainEventSigner.SIGNATURE_VERSION_HEADER);
        byte[] body = message.getBody();

        if (signature == null || signature.isBlank()) {
            return refuse(Decision.REJECT_MISSING);
        }
        if (!DomainEventVerifier.supportsVersion(version)) {
            return refuse(Decision.REJECT_UNSUPPORTED_VERSION);
        }
        if (!signer.matches(hop.originalExchange(), hop.originalRoutingKey(), signedAt, body, signature)
                || !stamp.matches(hop, signedAt, signature, body, header(properties, RetryHop.STAMP_HEADER))) {
            return refuse(Decision.REJECT_INVALID);
        }
        DelayedRetrySchedule schedule = topologies.forQueue(hop.queue()).orElseThrow().schedule();
        // Only a schedule shortened while the copy waited gets here: no stage left to vouch for it.
        if (!schedule.hasStage(hop.attempt())) {
            return refuse(Decision.REJECT_INVALID);
        }
        return isFresh(hop, schedule) ? Decision.ACCEPT : refuse(Decision.REJECT_STALE);
    }

    private boolean isFresh(RetryHop hop, DelayedRetrySchedule schedule) {
        long now = clock.millis();
        // Another replica may have stamped the hop: the fleet's clock skew applies ahead of now.
        return hop.retriedAt() - now <= freshnessWindow.toMillis()
                && now - hop.retriedAt() <= schedule.maxWait(hop.attempt()).toMillis();
    }

    private Decision refuse(Decision rejection) {
        return verifySignatures ? rejection : Decision.ACCEPT_UNVERIFIED;
    }

    private static String header(MessageProperties properties, String name) {
        Object value = properties.getHeader(name);
        return value == null ? null : value.toString();
    }
}
