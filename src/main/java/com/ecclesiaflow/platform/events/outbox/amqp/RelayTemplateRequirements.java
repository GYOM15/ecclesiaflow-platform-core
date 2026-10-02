package com.ecclesiaflow.platform.events.outbox.amqp;

import com.ecclesiaflow.platform.events.signing.DomainEventSigner;
import com.ecclesiaflow.platform.events.signing.amqp.SigningMessagePostProcessor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.Collection;

/**
 * Checked at startup because each gap fails silently at run time: no confirms, every wait times
 * out; no returns, an unroutable message counts as sent; no signing, strict consumers drop it.
 */
public final class RelayTemplateRequirements {

    private RelayTemplateRequirements() {
    }

    /**
     * @param signer the module's signer, or {@code null} when signing is not configured
     * @throws IllegalStateException naming the first unmet requirement
     */
    public static void check(RabbitTemplate template, DomainEventSigner signer) {
        ConnectionFactory connectionFactory = template.getConnectionFactory();
        if (!connectionFactory.isPublisherConfirms()) {
            throw new IllegalStateException("The outbox relay needs correlated publisher confirms "
                    + "(spring.rabbitmq.publisher-confirm-type=correlated)");
        }
        if (!connectionFactory.isPublisherReturns()) {
            throw new IllegalStateException("The outbox relay needs publisher returns to detect unroutable messages "
                    + "(spring.rabbitmq.publisher-returns=true)");
        }
        if (!Boolean.TRUE.equals(template.isMandatoryFor(new Message(new byte[0])))) {
            throw new IllegalStateException("The outbox relay template must publish mandatory "
                    + "(RabbitTemplate.setMandatory(true)), or an unroutable message is dropped without a return");
        }
        if (template.isChannelTransacted()) {
            throw new IllegalStateException("The outbox relay template must not use transacted channels, "
                    + "which cannot carry publisher confirms");
        }
        if (signer != null && signer.isEnabled() && !signs(template.getBeforePublishPostProcessors())) {
            throw new IllegalStateException("Domain-event signing is configured but the outbox relay template has no "
                    + SigningMessagePostProcessor.class.getSimpleName()
                    + "; register it with addBeforePublishPostProcessors so relayed events are signed");
        }
    }

    private static boolean signs(Collection<?> postProcessors) {
        return postProcessors != null
                && postProcessors.stream().anyMatch(SigningMessagePostProcessor.class::isInstance);
    }
}
