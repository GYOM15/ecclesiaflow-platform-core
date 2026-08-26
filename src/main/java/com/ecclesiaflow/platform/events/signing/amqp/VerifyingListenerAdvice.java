package com.ecclesiaflow.platform.events.signing.amqp;

import com.ecclesiaflow.platform.events.signing.DomainEventSigner;
import com.ecclesiaflow.platform.events.signing.DomainEventVerifier;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;

/**
 * Consume-side verification hook for {@code @RabbitListener} containers
 * (security finding C07). Add it to a listener container factory's advice chain
 * (e.g. {@code SimpleRabbitListenerContainerFactory.setAdviceChain(...)}); it
 * runs <em>before</em> the message is deserialized and handed to the listener,
 * so a forged event never reaches business logic.
 *
 * <p>Decision flow (delegated to {@link DomainEventVerifier}):</p>
 * <ul>
 *   <li>signing disabled, or signature valid → proceed to the listener;</li>
 *   <li>lenient mode + missing/invalid signature → proceed, but log a WARN
 *       (so the gap is observable while publishers are migrated);</li>
 *   <li>strict mode + missing/invalid signature → throw
 *       {@link AmqpRejectAndDontRequeueException} so the broker dead-letters the
 *       message instead of redelivering it forever.</li>
 * </ul>
 *
 * <p>The advised invocation's first argument is the raw AMQP {@link Message};
 * if it is absent (non-listener invocation) the advice is a transparent
 * pass-through.</p>
 */
public class VerifyingListenerAdvice implements MethodInterceptor {

    private static final Logger log = LoggerFactory.getLogger(VerifyingListenerAdvice.class);

    private final DomainEventVerifier verifier;

    public VerifyingListenerAdvice(DomainEventVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Message message = extractMessage(invocation);
        if (message == null) {
            return invocation.proceed();
        }

        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        String signature = headerAsString(
                message.getMessageProperties().getHeader(DomainEventSigner.SIGNATURE_HEADER));
        byte[] body = message.getBody();

        DomainEventVerifier.Decision decision = verifier.verify(body, signature);

        switch (decision) {
            case ACCEPT -> { /* signature valid or signing off — deliver silently */ }
            case ACCEPT_UNVERIFIED -> log.warn(
                    "DOMAIN-EVENT-SIGNATURE: accepting UNVERIFIED message routing_key={} "
                            + "(verify-signatures=false; signature {}). Sign publishers, then enable strict mode.",
                    routingKey, signature == null ? "missing" : "invalid");
            case REJECT_MISSING -> {
                log.error("DOMAIN-EVENT-SIGNATURE: REJECTING unsigned message routing_key={} "
                        + "(verify-signatures=true)", routingKey);
                throw new AmqpRejectAndDontRequeueException(
                        "Unsigned domain event rejected (verify-signatures=true)");
            }
            case REJECT_INVALID -> {
                log.error("DOMAIN-EVENT-SIGNATURE: REJECTING message with INVALID signature "
                        + "routing_key={} (verify-signatures=true)", routingKey);
                throw new AmqpRejectAndDontRequeueException(
                        "Domain event with invalid signature rejected (verify-signatures=true)");
            }
        }

        return invocation.proceed();
    }

    private static Message extractMessage(MethodInvocation invocation) {
        for (Object arg : invocation.getArguments()) {
            if (arg instanceof Message m) {
                return m;
            }
        }
        return null;
    }

    private static String headerAsString(Object header) {
        return header == null ? null : header.toString();
    }
}
