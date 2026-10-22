package com.ecclesiaflow.platform.events.signing.amqp;

import com.ecclesiaflow.platform.events.signing.DomainEventVerifier;
import org.springframework.amqp.core.Message;

import java.util.Optional;

/**
 * Verifies a message its consumer's own retry machinery sent back to the queue: its route and its age
 * no longer match its signature, so the first-delivery rules would refuse it. Answers empty for any
 * other message, which {@link VerifyingListenerAdvice} then verifies as a first delivery.
 */
@FunctionalInterface
public interface RedeliveryVerifier {

    Optional<DomainEventVerifier.Decision> verifyRedelivery(Message message);
}
