package com.ecclesiaflow.platform.events.signing.amqp;

import com.ecclesiaflow.platform.events.signing.DomainEventSigner;
import com.ecclesiaflow.platform.events.signing.DomainEventVerifier;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;

import java.util.Locale;

/**
 * Consume-side verification hook for {@code @RabbitListener} containers
 * (security findings C07 and F054). Add it to a listener container factory's
 * advice chain (e.g. {@code SimpleRabbitListenerContainerFactory.setAdviceChain(...)});
 * it runs <em>before</em> the message is deserialized and handed to the
 * listener, so a forged event never reaches business logic.
 *
 * <p>Decision flow (delegated to {@link DomainEventVerifier}):</p>
 * <ul>
 *   <li>signing disabled, or signature valid and fresh → proceed to the listener;</li>
 *   <li>lenient mode + missing/invalid/stale signature → proceed, but log a WARN
 *       (so the gap is observable while publishers are migrated);</li>
 *   <li>strict mode + missing/invalid/stale signature → throw
 *       {@link AmqpRejectAndDontRequeueException} so the broker dead-letters the
 *       message instead of redelivering it forever.</li>
 * </ul>
 *
 * <p>The destination handed to the verifier is the one the <em>broker</em>
 * delivered on ({@code getReceivedExchange()} / {@code getReceivedRoutingKey()}),
 * never a header: a header is publisher-controlled, and checking a signature
 * against a publisher-supplied destination verifies nothing.</p>
 *
 * <p>The advised invocation's first argument is the raw AMQP {@link Message};
 * if it is absent (non-listener invocation) the advice is a transparent
 * pass-through.</p>
 *
 * <h2>Metric</h2>
 *
 * <p>Every decision increments
 * {@code ecclesiaflow_domain_events_signature_total{decision}} (finding F088) —
 * without it a fleet running lenient is indistinguishable from a fleet running
 * verified.</p>
 *
 * <p><strong>Caveat for whoever writes the alert, corrected.</strong> An earlier
 * version of this comment said that with a blank secret the advice is not
 * registered, so the counter would be absent rather than zero, and told the
 * reader to pair the rule with {@code absent()}. That was measured and it is
 * false. Every module ships {@code hmac-secret=${EVENTS_HMAC_SECRET:}}, so with
 * the variable unset the property is still PRESENT with an empty value;
 * {@code @ConditionalOnProperty} matches, this advice IS registered, the signer
 * reports disabled, and every message is counted {@code decision="accept"}. The
 * counter therefore never goes absent and never shows
 * {@code accept_unverified} — a fleet checking nothing looks exactly like a
 * healthy verified one.</p>
 *
 * <p>So detect that state from CONFIGURATION, not from this metric: a non-empty
 * {@code EVENTS_HMAC_SECRET}, which {@code docker-compose.prod.yml} enforces
 * with {@code ${EVENTS_HMAC_SECRET:?}}. What the metric is good for is the
 * migration window — watching {@code accept_unverified} fall to zero before
 * {@code verify-signatures} is turned on.</p>
 *
 * <p>There is deliberately no {@code routing_key} tag. The value would be
 * {@code getReceivedRoutingKey()} — chosen by whoever PUBLISHED the message,
 * which under this class's own threat model is the attacker, on queues bound
 * with topic wildcards, and counted BEFORE the reject. Each forged key would
 * mint a permanent time series. The routing key is already in every log line
 * this advice writes, where retention is bounded by log rotation rather than by
 * a registry that only grows.</p>
 */
public class VerifyingListenerAdvice implements MethodInterceptor {

    private static final Logger log = LoggerFactory.getLogger(VerifyingListenerAdvice.class);

    /** Counter name, dotted per Micrometer convention; Prometheus renders it with {@code _total}. */
    static final String METRIC = "ecclesiaflow.domain.events.signature";

    private final DomainEventVerifier verifier;
    private final MeterRegistry meterRegistry;

    public VerifyingListenerAdvice(DomainEventVerifier verifier) {
        this(verifier, null);
    }

    /** @param meterRegistry may be {@code null} — the advice then simply counts nothing. */
    public VerifyingListenerAdvice(DomainEventVerifier verifier, MeterRegistry meterRegistry) {
        this.verifier = verifier;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Message message = extractMessage(invocation);
        if (message == null) {
            return invocation.proceed();
        }

        String exchange = message.getMessageProperties().getReceivedExchange();
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        String signature = headerAsString(
                message.getMessageProperties().getHeader(DomainEventSigner.SIGNATURE_HEADER));
        String signedAt = headerAsString(
                message.getMessageProperties().getHeader(DomainEventSigner.SIGNED_AT_HEADER));
        byte[] body = message.getBody();

        DomainEventVerifier.Decision decision =
                verifier.verify(exchange, routingKey, body, signature, signedAt);
        count(decision);

        switch (decision) {
            case ACCEPT -> { /* signature valid or signing off — deliver silently */ }
            case ACCEPT_UNVERIFIED -> log.warn(
                    "DOMAIN-EVENT-SIGNATURE: accepting UNVERIFIED message exchange={} routing_key={} "
                            + "(verify-signatures=false; signature {}). Sign publishers, then enable strict mode.",
                    exchange, routingKey, signature == null ? "missing" : "invalid or stale");
            case REJECT_MISSING -> {
                log.error("DOMAIN-EVENT-SIGNATURE: REJECTING unsigned message exchange={} routing_key={} "
                        + "(verify-signatures=true)", exchange, routingKey);
                throw new AmqpRejectAndDontRequeueException(
                        "Unsigned domain event rejected (verify-signatures=true)");
            }
            case REJECT_INVALID -> {
                log.error("DOMAIN-EVENT-SIGNATURE: REJECTING message with INVALID signature "
                        + "exchange={} routing_key={} (verify-signatures=true)", exchange, routingKey);
                throw new AmqpRejectAndDontRequeueException(
                        "Domain event with invalid signature rejected (verify-signatures=true)");
            }
            case REJECT_STALE -> {
                log.error("DOMAIN-EVENT-SIGNATURE: REJECTING REPLAYED message exchange={} routing_key={} "
                        + "signed_at={} — signature is genuine but outside the freshness window "
                        + "(verify-signatures=true). Either a replay, or this host's clock is adrift.",
                        exchange, routingKey, signedAt);
                throw new AmqpRejectAndDontRequeueException(
                        "Domain event outside the signature freshness window rejected (verify-signatures=true)");
            }
        }

        return invocation.proceed();
    }

    private void count(DomainEventVerifier.Decision decision) {
        if (meterRegistry == null) {
            return;
        }
        // `decision` only: it is an enum, so the cardinality is five. The routing
        // key is publisher-controlled — see the javadoc — and tagging with it
        // would let a forger create unbounded, permanent series from rejected
        // messages.
        Counter.builder(METRIC)
                .tag("decision", decision.name().toLowerCase(Locale.ROOT))
                .register(meterRegistry)
                .increment();
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
