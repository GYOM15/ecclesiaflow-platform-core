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

import static com.ecclesiaflow.platform.logging.SecurityMaskingUtils.escapeControlChars;

/**
 * Consume-side signature check for {@code @RabbitListener} containers: add it to the container
 * factory's advice chain so a forged event is refused before it is deserialized. Strict-mode rejections
 * throw {@link AmqpRejectAndDontRequeueException} so the broker dead-letters instead of redelivering.
 *
 * <p>The {@link #METRIC} counter cannot reveal an unsigned fleet: with {@code EVENTS_HMAC_SECRET} empty
 * the advice is still registered and counts every message as {@code accept}. Detect that from
 * configuration; use the metric to watch {@code accept_unverified} reach zero before turning on
 * {@code verify-signatures}.</p>
 */
public class VerifyingListenerAdvice implements MethodInterceptor {

    private static final Logger log = LoggerFactory.getLogger(VerifyingListenerAdvice.class);

    /** Exported to Prometheus as {@code ecclesiaflow_domain_events_signature_total}. */
    static final String METRIC = "ecclesiaflow.domain.events.signature";

    private final DomainEventVerifier verifier;
    private final MeterRegistry meterRegistry;

    public VerifyingListenerAdvice(DomainEventVerifier verifier) {
        this(verifier, null);
    }

    /** A {@code null} registry counts nothing. */
    public VerifyingListenerAdvice(DomainEventVerifier verifier, MeterRegistry meterRegistry) {
        this.verifier = verifier;
        this.meterRegistry = meterRegistry;
        // Published at zero so increase() sees a process's first rejection.
        if (meterRegistry != null) {
            for (DomainEventVerifier.Decision decision : DomainEventVerifier.Decision.values()) {
                counter(decision);
            }
        }
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
        String version = headerAsString(
                message.getMessageProperties().getHeader(DomainEventSigner.SIGNATURE_VERSION_HEADER));
        byte[] body = message.getBody();

        DomainEventVerifier.Decision decision =
                verifier.verify(exchange, routingKey, body, signature, signedAt, version);
        count(decision);

        switch (decision) {
            case ACCEPT -> { }
            case ACCEPT_UNVERIFIED -> log.warn(
                    "DOMAIN-EVENT-SIGNATURE: accepting UNVERIFIED message exchange={} routing_key={} "
                            + "(verify-signatures=false; signature {}). Sign publishers, then enable strict mode.",
                    escapeControlChars(exchange), escapeControlChars(routingKey),
                    signature == null ? "missing" : "invalid, stale or of an unknown version");
            case REJECT_MISSING -> {
                log.error("DOMAIN-EVENT-SIGNATURE: REJECTING unsigned message exchange={} routing_key={} "
                        + "(verify-signatures=true)", escapeControlChars(exchange), escapeControlChars(routingKey));
                throw new AmqpRejectAndDontRequeueException(
                        "Unsigned domain event rejected (verify-signatures=true)");
            }
            case REJECT_INVALID -> {
                log.error("DOMAIN-EVENT-SIGNATURE: REJECTING message with INVALID signature "
                        + "exchange={} routing_key={} (verify-signatures=true)",
                        escapeControlChars(exchange), escapeControlChars(routingKey));
                throw new AmqpRejectAndDontRequeueException(
                        "Domain event with invalid signature rejected (verify-signatures=true)");
            }
            case REJECT_STALE -> {
                log.error("DOMAIN-EVENT-SIGNATURE: REJECTING REPLAYED message exchange={} routing_key={} "
                        + "signed_at={} — signature is genuine but outside the freshness window "
                        + "(verify-signatures=true). Either a replay, or this host's clock is adrift.",
                        escapeControlChars(exchange), escapeControlChars(routingKey), escapeControlChars(signedAt));
                throw new AmqpRejectAndDontRequeueException(
                        "Domain event outside the signature freshness window rejected (verify-signatures=true)");
            }
            case REJECT_UNSUPPORTED_VERSION -> {
                log.error("DOMAIN-EVENT-SIGNATURE: REJECTING message signed in an unsupported format version "
                        + "exchange={} routing_key={} (verify-signatures=true). Upgrade this consumer before "
                        + "its publishers.", escapeControlChars(exchange), escapeControlChars(routingKey));
                throw new AmqpRejectAndDontRequeueException(
                        "Domain event with an unsupported signature version rejected (verify-signatures=true)");
            }
        }

        return invocation.proceed();
    }

    private void count(DomainEventVerifier.Decision decision) {
        if (meterRegistry == null) {
            return;
        }
        counter(decision).increment();
    }

    // No routing_key tag: it is publisher-controlled, so a forger could mint unbounded series.
    private Counter counter(DomainEventVerifier.Decision decision) {
        return Counter.builder(METRIC)
                .tag("decision", decision.name().toLowerCase(Locale.ROOT))
                .register(meterRegistry);
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
