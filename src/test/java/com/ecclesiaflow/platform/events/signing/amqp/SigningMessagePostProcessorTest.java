package com.ecclesiaflow.platform.events.signing.amqp;

import com.ecclesiaflow.platform.events.signing.DomainEventSigner;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SigningMessagePostProcessorTest {

    private static final String SECRET = "post-processor-test-secret";
    private static final String EXCHANGE = "ecclesiaflow.domain-events";
    private static final String ROUTING_KEY = "member.profile.changed.v1";
    private static final Instant NOW = Instant.parse("2026-09-17T09:00:00Z");

    private final DomainEventSigner signer = new DomainEventSigner(SECRET);
    private final SigningMessagePostProcessor processor =
            new SigningMessagePostProcessor(signer, Clock.fixed(NOW, ZoneOffset.UTC));

    private static Message message(byte[] body) {
        return new Message(body, new MessageProperties());
    }

    private Message sign(Message in) {
        return processor.postProcessMessage(in, null, EXCHANGE, ROUTING_KEY);
    }

    @Test
    void stampsVerifiableSignatureHeader() {
        byte[] body = "body-bytes".getBytes(StandardCharsets.UTF_8);
        Message out = sign(message(body));

        String sig = (String) out.getMessageProperties().getHeader(DomainEventSigner.SIGNATURE_HEADER);
        String signedAt = (String) out.getMessageProperties().getHeader(DomainEventSigner.SIGNED_AT_HEADER);
        assertThat(sig).isNotBlank();
        assertThat(signedAt).isEqualTo(Long.toString(NOW.toEpochMilli()));
        assertThat(signer.matches(EXCHANGE, ROUTING_KEY, signedAt, out.getBody(), sig)).isTrue();
    }

    /**
     * The destination reaches a template-wide post-processor only through the
     * four-argument overload. If it ever stopped being bound, every signature
     * would still verify against the wrong routing key — which is the finding.
     */
    @Test
    void signatureDoesNotVerifyUnderAnotherRoutingKey() {
        Message out = sign(message("body-bytes".getBytes(StandardCharsets.UTF_8)));

        String sig = (String) out.getMessageProperties().getHeader(DomainEventSigner.SIGNATURE_HEADER);
        String signedAt = (String) out.getMessageProperties().getHeader(DomainEventSigner.SIGNED_AT_HEADER);
        assertThat(signer.matches(EXCHANGE, "member.removed.v1", signedAt, out.getBody(), sig)).isFalse();
    }

    @Test
    void doesNotMutateBody() {
        byte[] original = "body-bytes".getBytes(StandardCharsets.UTF_8);
        Message out = sign(message("body-bytes".getBytes(StandardCharsets.UTF_8)));
        assertThat(out.getBody()).isEqualTo(original);
    }

    @Test
    void noOpWhenSigningDisabled() {
        SigningMessagePostProcessor disabled =
                new SigningMessagePostProcessor(new DomainEventSigner(""));
        Message out = disabled.postProcessMessage(
                message("body".getBytes(StandardCharsets.UTF_8)), null, EXCHANGE, ROUTING_KEY);
        assertThat((Object) out.getMessageProperties().getHeader(DomainEventSigner.SIGNATURE_HEADER)).isNull();
        assertThat((Object) out.getMessageProperties().getHeader(DomainEventSigner.SIGNED_AT_HEADER)).isNull();
    }

    @Test
    void oneArgOverloadRefusesToSignWithoutADestination() {
        // signing against the empty destination is exactly the hole being closed,
        // so this fails loudly at the publish site instead of quietly producing a
        // message every strict consumer would dead-letter
        assertThatThrownBy(() -> processor.postProcessMessage(message("b".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(AmqpException.class)
                .hasMessageContaining("exchange and routing key");
    }

    @Test
    void oneArgOverloadStaysAPassThroughWhenSigningDisabled() {
        // a disabled signer must not turn a publish into an exception
        SigningMessagePostProcessor disabled =
                new SigningMessagePostProcessor(new DomainEventSigner(""));
        Message in = message("b".getBytes(StandardCharsets.UTF_8));
        assertThat(disabled.postProcessMessage(in)).isSameAs(in);
    }

    @Test
    void defaultConstructorUsesTheSystemClock() {
        SigningMessagePostProcessor real = new SigningMessagePostProcessor(signer);
        Message out = real.postProcessMessage(
                message("b".getBytes(StandardCharsets.UTF_8)), null, EXCHANGE, ROUTING_KEY);
        long stamped = Long.parseLong(
                (String) out.getMessageProperties().getHeader(DomainEventSigner.SIGNED_AT_HEADER));
        assertThat(Math.abs(System.currentTimeMillis() - stamped)).isLessThan(60_000L);
    }
}
