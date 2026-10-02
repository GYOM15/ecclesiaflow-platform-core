package com.ecclesiaflow.platform.events.signing.autoconfigure;

import com.ecclesiaflow.platform.events.signing.DomainEventSigner;
import com.ecclesiaflow.platform.events.signing.DomainEventVerifier;
import com.ecclesiaflow.platform.events.signing.EventSigningProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
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
 * <p>Registers the {@link DomainEventSigner} and {@link DomainEventVerifier}. The AMQP
 * pieces publishers and consumers plug in come from
 * {@link PlatformEventSigningAmqpAutoConfiguration}.</p>
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
}
