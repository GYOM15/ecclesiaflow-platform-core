package com.ecclesiaflow.platform.events.signing.autoconfigure;

import com.ecclesiaflow.platform.events.signing.DomainEventSigner;
import com.ecclesiaflow.platform.events.signing.DomainEventVerifier;
import com.ecclesiaflow.platform.events.signing.EventSigningProperties;
import com.ecclesiaflow.platform.events.signing.amqp.SigningMessagePostProcessor;
import com.ecclesiaflow.platform.events.signing.amqp.VerifyingListenerAdvice;
import org.springframework.amqp.core.Message;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot auto-configuration for cross-module domain-event HMAC signing
 * (security finding C07).
 *
 * <p>Active only when {@code ecclesiaflow.events.hmac-secret} is set and
 * non-blank — leaving it unset keeps the library inert (the migration escape
 * hatch). All beans use {@link ConditionalOnMissingBean} so a consumer can
 * override any piece.</p>
 *
 * <p>The pure {@link DomainEventSigner}/{@link DomainEventVerifier} are always
 * registered (when enabled). The AMQP helpers
 * ({@link SigningMessagePostProcessor}, {@link VerifyingListenerAdvice}) are
 * registered only when Spring AMQP is on the consumer's classpath — they are
 * the wiring publishers/consumers plug in:</p>
 * <ul>
 *   <li>Publishers: add the {@link SigningMessagePostProcessor} to their
 *       domain-events {@code RabbitTemplate} via
 *       {@code addBeforePublishPostProcessors(...)}.</li>
 *   <li>Consumers: add the {@link VerifyingListenerAdvice} to their listener
 *       container factory's advice chain via {@code setAdviceChain(...)}.</li>
 * </ul>
 * No specific module is wired here — that is each module's step.
 */
@AutoConfiguration
@EnableConfigurationProperties(EventSigningProperties.class)
@ConditionalOnProperty(prefix = "ecclesiaflow.events", name = "hmac-secret")
public class PlatformEventSigningAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DomainEventSigner domainEventSigner(EventSigningProperties props) {
        return new DomainEventSigner(props.getHmacSecret());
    }

    @Bean
    @ConditionalOnMissingBean
    public DomainEventVerifier domainEventVerifier(DomainEventSigner signer,
                                                   EventSigningProperties props) {
        return new DomainEventVerifier(signer, props.isVerifySignatures());
    }

    /**
     * AMQP wiring helpers — registered only when Spring AMQP's {@link Message}
     * is on the classpath (so the pure signer/verifier stay usable in modules
     * or tests that have no messaging runtime) AND the signer/verifier beans
     * exist (i.e. {@code ecclesiaflow.events.hmac-secret} is set). The
     * {@link ConditionalOnBean} guard is what ties this nested config to the
     * enclosing one — a nested {@code @AutoConfiguration} does not inherit the
     * parent's {@code @ConditionalOnProperty}.
     */
    @AutoConfiguration(after = PlatformEventSigningAutoConfiguration.class)
    @ConditionalOnClass(Message.class)
    public static class AmqpHelpers {

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBean(DomainEventSigner.class)
        public SigningMessagePostProcessor signingMessagePostProcessor(DomainEventSigner signer) {
            return new SigningMessagePostProcessor(signer);
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBean(DomainEventVerifier.class)
        public VerifyingListenerAdvice verifyingListenerAdvice(DomainEventVerifier verifier) {
            return new VerifyingListenerAdvice(verifier);
        }
    }
}
