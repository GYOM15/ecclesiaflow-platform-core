package com.ecclesiaflow.platform.events.outbox.amqp;

import com.ecclesiaflow.platform.events.signing.DomainEventSigner;
import com.ecclesiaflow.platform.events.signing.amqp.SigningMessagePostProcessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RelayTemplateRequirementsTest {

    private static final DomainEventSigner SIGNING = new DomainEventSigner("relay-requirements-secret");
    private static final DomainEventSigner NOT_SIGNING = new DomainEventSigner("");

    private static RabbitTemplate template(boolean confirms, boolean returns, boolean mandatory) {
        ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
        when(connectionFactory.isPublisherConfirms()).thenReturn(confirms);
        when(connectionFactory.isPublisherReturns()).thenReturn(returns);
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMandatory(mandatory);
        return template;
    }

    private static RabbitTemplate reliableTemplate() {
        return template(true, true, true);
    }

    @Test
    @DisplayName("Accepts a template with confirms, returns and mandatory publishing")
    void acceptsReliableTemplate() {
        assertThatCode(() -> RelayTemplateRequirements.check(reliableTemplate(), null)).doesNotThrowAnyException();
        assertThatCode(() -> RelayTemplateRequirements.check(reliableTemplate(), NOT_SIGNING))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Refuses a template without correlated publisher confirms")
    void refusesWithoutConfirms() {
        assertThatThrownBy(() -> RelayTemplateRequirements.check(template(false, true, true), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("publisher-confirm-type=correlated");
    }

    @Test
    @DisplayName("Refuses a template without publisher returns")
    void refusesWithoutReturns() {
        assertThatThrownBy(() -> RelayTemplateRequirements.check(template(true, false, true), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("publisher-returns=true");
    }

    @Test
    @DisplayName("Refuses a template that does not publish mandatory")
    void refusesWithoutMandatory() {
        assertThatThrownBy(() -> RelayTemplateRequirements.check(template(true, true, false), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mandatory");
    }

    @Test
    @DisplayName("Refuses a transacted channel, which cannot carry publisher confirms")
    void refusesTransactedChannel() {
        RabbitTemplate template = reliableTemplate();
        template.setChannelTransacted(true);

        assertThatThrownBy(() -> RelayTemplateRequirements.check(template, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transacted");
    }

    @Test
    @DisplayName("Refuses a template that would relay unsigned while signing is configured")
    void refusesUnsignedTemplateWhenSigningIsOn() {
        assertThatThrownBy(() -> RelayTemplateRequirements.check(reliableTemplate(), SIGNING))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(SigningMessagePostProcessor.class.getSimpleName());
    }

    @Test
    @DisplayName("Refuses a template whose post-processors do not include the signing one")
    void refusesTemplateWithOtherPostProcessorsOnly() {
        RabbitTemplate template = reliableTemplate();
        template.addBeforePublishPostProcessors(message -> message);

        assertThatThrownBy(() -> RelayTemplateRequirements.check(template, SIGNING))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(SigningMessagePostProcessor.class.getSimpleName());
    }

    @Test
    @DisplayName("Accepts a signing template when signing is configured")
    void acceptsSigningTemplate() {
        RabbitTemplate template = reliableTemplate();
        template.addBeforePublishPostProcessors(new SigningMessagePostProcessor(SIGNING));

        assertThatCode(() -> RelayTemplateRequirements.check(template, SIGNING)).doesNotThrowAnyException();
    }
}
