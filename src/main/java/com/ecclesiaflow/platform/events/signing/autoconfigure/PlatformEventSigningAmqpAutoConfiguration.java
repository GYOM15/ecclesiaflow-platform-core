package com.ecclesiaflow.platform.events.signing.autoconfigure;

import com.ecclesiaflow.platform.events.signing.DomainEventSigner;
import com.ecclesiaflow.platform.events.signing.DomainEventVerifier;
import com.ecclesiaflow.platform.events.signing.amqp.SigningMessagePostProcessor;
import com.ecclesiaflow.platform.events.signing.amqp.VerifyingListenerAdvice;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * AMQP wiring for domain-event signing, registered once the signer and verifier exist.
 *
 * <p>A top-level class on purpose: nested inside {@link PlatformEventSigningAutoConfiguration}
 * it was parsed before the enclosing class's beans, so its {@link ConditionalOnBean}
 * never matched and nothing was signed.</p>
 */
@AutoConfiguration(after = PlatformEventSigningAutoConfiguration.class)
@ConditionalOnClass(Message.class)
public class PlatformEventSigningAmqpAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DomainEventSigner.class)
    public SigningMessagePostProcessor signingMessagePostProcessor(DomainEventSigner signer) {
        return new SigningMessagePostProcessor(signer);
    }

    /** Takes the registry through an {@link ObjectProvider}: without metrics only the counter is lost. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DomainEventVerifier.class)
    public VerifyingListenerAdvice verifyingListenerAdvice(DomainEventVerifier verifier,
                                                           ObjectProvider<MeterRegistry> meterRegistry) {
        return new VerifyingListenerAdvice(verifier, meterRegistry.getIfAvailable());
    }
}
