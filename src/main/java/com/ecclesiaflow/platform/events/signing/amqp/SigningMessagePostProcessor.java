package com.ecclesiaflow.platform.events.signing.amqp;

import com.ecclesiaflow.platform.events.signing.DomainEventSigner;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Publish-side hook that stamps the {@value DomainEventSigner#SIGNATURE_HEADER}
 * header onto every outbound domain event (security finding C07). Wire it into a
 * publisher's domain-events {@link RabbitTemplate} via
 * {@link RabbitTemplate#addBeforePublishPostProcessors} (or
 * {@code setBeforePublishPostProcessors}) so it runs on the fully serialized
 * message just before publication.
 *
 * <p>The body is left untouched; only the header is added — existing consumers
 * deserialize the exact same bytes. When signing is disabled (blank secret)
 * this is a no-op, so a publisher can run unsigned during migration.</p>
 */
public class SigningMessagePostProcessor implements MessagePostProcessor {

    private final DomainEventSigner signer;

    public SigningMessagePostProcessor(DomainEventSigner signer) {
        this.signer = signer;
    }

    @Override
    public Message postProcessMessage(Message message) throws AmqpException {
        if (!signer.isEnabled()) {
            return message;
        }
        String signature = signer.sign(message.getBody());
        message.getMessageProperties().setHeader(DomainEventSigner.SIGNATURE_HEADER, signature);
        return message;
    }
}
