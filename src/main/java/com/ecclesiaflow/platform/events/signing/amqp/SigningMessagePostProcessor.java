package com.ecclesiaflow.platform.events.signing.amqp;

import com.ecclesiaflow.platform.events.signing.DomainEventSigner;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Correlation;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Clock;

/**
 * Publish-side hook that stamps the signature headers on every outbound domain event; register it with
 * {@link RabbitTemplate#addBeforePublishPostProcessors}. A no-op while signing is disabled.
 *
 * <p>The exchange and routing key are not on the outbound message properties: Spring AMQP passes them
 * only to the four-argument overload, which {@code RabbitTemplate.doSend} calls for every before-publish
 * post-processor. The one-argument overload therefore throws: a signature bound to an empty destination
 * reopens the replay hole, and every strict consumer would dead-letter the message.</p>
 */
public class SigningMessagePostProcessor implements MessagePostProcessor {

    private final DomainEventSigner signer;
    private final Clock clock;

    public SigningMessagePostProcessor(DomainEventSigner signer) {
        this(signer, Clock.systemUTC());
    }

    public SigningMessagePostProcessor(DomainEventSigner signer, Clock clock) {
        this.signer = signer;
        this.clock = clock;
    }

    @Override
    public Message postProcessMessage(Message message) throws AmqpException {
        if (!signer.isEnabled()) {
            return message;
        }
        throw new AmqpException(
                "Domain events must be signed against their destination: this post-processor needs the "
                        + "exchange and routing key, which only the four-argument postProcessMessage overload "
                        + "receives. Register it with RabbitTemplate.addBeforePublishPostProcessors(...) "
                        + "instead of calling it by hand.");
    }

    @Override
    public Message postProcessMessage(Message message, Correlation correlation,
                                      String exchange, String routingKey) {
        if (!signer.isEnabled()) {
            return message;
        }
        long signedAt = clock.millis();
        String signature = signer.sign(exchange, routingKey, signedAt, message.getBody());
        message.getMessageProperties().setHeader(DomainEventSigner.SIGNED_AT_HEADER, Long.toString(signedAt));
        message.getMessageProperties().setHeader(DomainEventSigner.SIGNATURE_HEADER, signature);
        message.getMessageProperties().setHeader(
                DomainEventSigner.SIGNATURE_VERSION_HEADER, DomainEventSigner.SIGNATURE_VERSION);
        return message;
    }
}
