package com.ecclesiaflow.platform.events.signing.amqp;

import com.ecclesiaflow.platform.events.signing.DomainEventSigner;
import com.ecclesiaflow.platform.events.signing.DomainEventVerifier;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VerifyingListenerAdviceTest {

    private static final String SECRET = "advice-test-secret";
    private static final String EXCHANGE = "ecclesiaflow.domain-events";
    private static final String ROUTING_KEY = "auth.setup-token.issued.v1";
    private static final Instant NOW = Instant.parse("2026-09-17T09:00:00Z");

    private final DomainEventSigner signer = new DomainEventSigner(SECRET);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final MeterRegistry meters = new SimpleMeterRegistry();

    private static byte[] body() {
        return "wire".getBytes(StandardCharsets.UTF_8);
    }

    private String signature() {
        return signer.sign(EXCHANGE, ROUTING_KEY, NOW.toEpochMilli(), body());
    }

    private Message message(String exchange, String routingKey, String signature, String signedAt) {
        MessageProperties props = new MessageProperties();
        props.setReceivedExchange(exchange);
        props.setReceivedRoutingKey(routingKey);
        if (signature != null) {
            props.setHeader(DomainEventSigner.SIGNATURE_HEADER, signature);
        }
        if (signedAt != null) {
            props.setHeader(DomainEventSigner.SIGNED_AT_HEADER, signedAt);
        }
        return new Message(body(), props);
    }

    private Message signedMessage() {
        return message(EXCHANGE, ROUTING_KEY, signature(), Long.toString(NOW.toEpochMilli()));
    }

    private static MethodInvocation invocationWith(Object... args) throws Throwable {
        MethodInvocation inv = mock(MethodInvocation.class);
        when(inv.getArguments()).thenReturn(args);
        when(inv.proceed()).thenReturn("listener-result");
        return inv;
    }

    private VerifyingListenerAdvice advice(boolean strict) {
        return new VerifyingListenerAdvice(
                new DomainEventVerifier(signer, strict, Duration.ofMinutes(5), clock), meters);
    }

    private double counted(String decision) {
        io.micrometer.core.instrument.Counter counter = meters.find(VerifyingListenerAdvice.METRIC)
                .tag("decision", decision)
                .counter();
        return counter == null ? 0d : counter.count();
    }

    @Test
    void validSignatureProceeds() throws Throwable {
        MethodInvocation inv = invocationWith(signedMessage());

        Object result = advice(true).invoke(inv);

        assertThat(result).isEqualTo("listener-result");
        verify(inv, times(1)).proceed();
        assertThat(counted("accept")).isEqualTo(1d);
    }

    @Test
    void lenientUnsignedProceedsAndIsCounted() throws Throwable {
        MethodInvocation inv = invocationWith(message(EXCHANGE, ROUTING_KEY, null, null));

        advice(false).invoke(inv);

        verify(inv, times(1)).proceed();
        // the counter is the only thing that tells an operator a fleet is running
        // unverified — a log line nobody greps is not observability
        assertThat(counted("accept_unverified")).isEqualTo(1d);
    }

    @Test
    void strictUnsignedRejectedWithoutRequeue() throws Throwable {
        MethodInvocation inv = invocationWith(message(EXCHANGE, ROUTING_KEY, null, null));

        assertThatThrownBy(() -> advice(true).invoke(inv))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessageContaining("Unsigned");
        verify(inv, never()).proceed();
        assertThat(counted("reject_missing")).isEqualTo(1d);
    }

    @Test
    void strictInvalidSignatureRejectedWithoutRequeue() throws Throwable {
        MethodInvocation inv = invocationWith(message(EXCHANGE, ROUTING_KEY, "AAAA", "1"));

        assertThatThrownBy(() -> advice(true).invoke(inv))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessageContaining("invalid signature");
        verify(inv, never()).proceed();
        assertThat(counted("reject_invalid")).isEqualTo(1d);
    }

    /**
     * The finding: a genuinely signed event, re-delivered under a sibling
     * routing key. The advice must read the destination the BROKER reports, not
     * one the publisher put in a header — and refuse.
     */
    @Test
    void strictGenuineEventReplayedUnderAnotherRoutingKeyRejected() throws Throwable {
        Message replayed = message(EXCHANGE, "auth.account.deleted.v1",
                signature(), Long.toString(NOW.toEpochMilli()));
        MethodInvocation inv = invocationWith(replayed);

        assertThatThrownBy(() -> advice(true).invoke(inv))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verify(inv, never()).proceed();
    }

    @Test
    void strictStaleSignatureRejectedAsReplay() throws Throwable {
        long old = NOW.minus(Duration.ofHours(6)).toEpochMilli();
        Message stale = message(EXCHANGE, ROUTING_KEY,
                signer.sign(EXCHANGE, ROUTING_KEY, old, body()), Long.toString(old));
        MethodInvocation inv = invocationWith(stale);

        assertThatThrownBy(() -> advice(true).invoke(inv))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessageContaining("freshness window");
        verify(inv, never()).proceed();
        assertThat(counted("reject_stale")).isEqualTo(1d);
    }

    @Test
    void noMessageArgumentIsTransparentPassThrough() throws Throwable {
        // an invocation with no AMQP Message argument must not be blocked
        MethodInvocation inv = invocationWith("not-a-message", 42);

        Object result = advice(true).invoke(inv);

        assertThat(result).isEqualTo("listener-result");
        verify(inv, times(1)).proceed();
    }

    @Test
    void disabledSignerAlwaysProceedsEvenStrict() throws Throwable {
        VerifyingListenerAdvice disabled = new VerifyingListenerAdvice(
                new DomainEventVerifier(new DomainEventSigner(""), true), meters);
        MethodInvocation inv = invocationWith(message(EXCHANGE, ROUTING_KEY, null, null));

        disabled.invoke(inv);

        verify(inv, times(1)).proceed();
    }

    @Test
    void worksWithoutAMeterRegistry() throws Throwable {
        // a consumer with no metrics runtime still verifies; only the counter is lost
        VerifyingListenerAdvice noMeters = new VerifyingListenerAdvice(
                new DomainEventVerifier(signer, true, Duration.ofMinutes(5), clock));
        MethodInvocation inv = invocationWith(signedMessage());

        assertThat(noMeters.invoke(inv)).isEqualTo("listener-result");
    }

    /**
     * INVERTED. These two asserted that the counter carries a {@code routing_key}
     * tag. It must not: the value is {@code getReceivedRoutingKey()}, chosen by
     * whoever PUBLISHED the message — the attacker, under this advice's own
     * threat model — on queues bound with topic wildcards, and counted BEFORE
     * the reject. Every forged key would have minted a permanent time series.
     * The comment that justified the tag ("routing keys are a closed,
     * code-defined set") was false for exactly the messages this class exists to
     * refuse.
     */
    @Test
    void theCounterCarriesNoPublisherControlledTag() throws Throwable {
        advice(true).invoke(invocationWith(signedMessage()));

        assertThat(meters.find(VerifyingListenerAdvice.METRIC).tag("routing_key", ROUTING_KEY).counter())
                .isNull();
        // and the decision tag, whose values are an enum, is there
        assertThat(meters.find(VerifyingListenerAdvice.METRIC).tag("decision", "accept").counter())
                .isNotNull();
    }

    @Test
    void aForgedRoutingKeyCannotCreateANewSeries() throws Throwable {
        VerifyingListenerAdvice strict = advice(true);
        for (String forged : new String[]{"a", "b", "c", "d"}) {
            MethodInvocation inv = invocationWith(
                    message(EXCHANGE, forged, signature(), Long.toString(NOW.toEpochMilli())));
            assertThatThrownBy(() -> strict.invoke(inv))
                    .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        }

        // Four different forged keys, one series: reject_invalid.
        assertThat(meters.find(VerifyingListenerAdvice.METRIC).counters()).hasSize(1);
        assertThat(meters.find(VerifyingListenerAdvice.METRIC).tag("decision", "reject_invalid")
                .counter().count()).isEqualTo(4d);
    }

    @Test
    void aMessageWithNoRoutingKeyIsStillCounted() throws Throwable {
        // it used to need a "unknown" fallback because a null tag value throws
        // inside Micrometer; with no wire-derived tag there is nothing to fall back on
        MethodInvocation inv = invocationWith(message(EXCHANGE, null, null, null));

        advice(false).invoke(inv);

        assertThat(counted("accept_unverified")).isEqualTo(1d);
    }
}
