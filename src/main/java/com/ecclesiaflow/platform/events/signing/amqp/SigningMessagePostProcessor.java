package com.ecclesiaflow.platform.events.signing.amqp;

import com.ecclesiaflow.platform.events.signing.DomainEventSigner;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Correlation;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Clock;

/**
 * Publish-side hook that stamps the {@value DomainEventSigner#SIGNATURE_HEADER}
 * and {@value DomainEventSigner#SIGNED_AT_HEADER} headers onto every outbound
 * domain event (security findings C07 and F054). Wire it into a publisher's
 * domain-events {@link RabbitTemplate} via
 * {@link RabbitTemplate#addBeforePublishPostProcessors} (or
 * {@code setBeforePublishPostProcessors}) so it runs on the fully serialized
 * message just before publication.
 *
 * <p>The body is left untouched; only headers are added — existing consumers
 * deserialize the exact same bytes. When signing is disabled (blank secret)
 * this is a no-op, so a publisher can run unsigned during migration.</p>
 *
 * <h2>Where the destination comes from</h2>
 *
 * <p>The signature binds the exchange and routing key, and neither is on the
 * outbound {@link org.springframework.amqp.core.MessageProperties} — they are
 * arguments to {@code convertAndSend}. Spring AMQP hands them to the
 * <em>four-argument</em> {@link MessagePostProcessor#postProcessMessage(Message,
 * Correlation, String, String)} overload, which {@code RabbitTemplate.doSend}
 * calls for every before-publish post-processor. Overriding that overload is
 * what lets one template-wide post-processor sign correctly for every
 * destination; the alternative — passing a per-call post-processor at each
 * {@code convertAndSend} site — signs nothing at the site somebody forgets.</p>
 *
 * <p>The one-argument overload therefore <strong>throws</strong>: reached only
 * by a hand-rolled call, it has no destination to bind, and a signature bound to
 * the empty destination is exactly the hole F054 closes. Failing at the publish
 * site is loud and local; the alternative is a message every strict consumer
 * silently dead-letters.</p>
 */
public class SigningMessagePostProcessor implements MessagePostProcessor {

    private final DomainEventSigner signer;
    private final Clock clock;

    public SigningMessagePostProcessor(DomainEventSigner signer) {
        this(signer, Clock.systemUTC());
    }

    /** @param clock source of the {@value DomainEventSigner#SIGNED_AT_HEADER} stamp; injected for tests. */
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
        return message;
    }
}
