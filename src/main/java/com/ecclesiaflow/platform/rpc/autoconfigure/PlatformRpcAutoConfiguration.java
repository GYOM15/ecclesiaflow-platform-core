package com.ecclesiaflow.platform.rpc.autoconfigure;

import com.ecclesiaflow.platform.rpc.events.S2sAuthEventListener;
import com.ecclesiaflow.platform.rpc.events.S2sAuthEvents;
import com.ecclesiaflow.platform.rpc.logging.PlatformRpcLoggingAspect;
import com.ecclesiaflow.platform.rpc.s2s.S2sProperties;
import com.ecclesiaflow.platform.rpc.s2s.interceptor.S2sAuthClientInterceptor;
import com.ecclesiaflow.platform.rpc.s2s.interceptor.S2sAuthServerInterceptor;
import com.ecclesiaflow.platform.rpc.s2s.interceptor.S2sAzpAllowList;
import com.ecclesiaflow.platform.rpc.s2s.interceptor.S2sJwtDecoder;
import com.ecclesiaflow.platform.rpc.s2s.interceptor.S2sScopeRegistry;
import com.ecclesiaflow.platform.rpc.s2s.token.S2sTokenCache;
import com.ecclesiaflow.platform.rpc.s2s.token.S2sTokenClient;
import com.ecclesiaflow.platform.rpc.s2s.token.S2sTokenProvider;
import io.grpc.BindableService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Publishes no {@link JwtDecoder}: the s2s decoder is an {@link S2sJwtDecoder}, so the module's REST
 * plane keeps its own decoder and a REST {@code JwtDecoder} can never stand in for the s2s one.
 */
@AutoConfiguration
@EnableConfigurationProperties(S2sProperties.class)
@ConditionalOnProperty(prefix = "ecclesiaflow.platform.rpc.s2s", name = "client-id")
public class PlatformRpcAutoConfiguration {
    // No @EnableAspectJAutoProxy: Boot enables AOP, and declaring it would override the consumer's proxy choice.

    @Bean
    @ConditionalOnMissingBean
    public S2sTokenClient s2sTokenClient(S2sProperties props) {
        return new S2sTokenClient(props);
    }

    @Bean
    @ConditionalOnMissingBean
    public S2sTokenCache s2sTokenCache() {
        return new S2sTokenCache();
    }

    @Bean
    @ConditionalOnMissingBean
    public S2sTokenProvider s2sTokenProvider(S2sTokenClient client, S2sTokenCache cache, S2sProperties props) {
        return new S2sTokenProvider(client, cache, props);
    }

    /** Pins {@code iss}; requires {@code aud} unless the expected audience is blank (migration escape hatch). */
    @Bean
    @ConditionalOnMissingBean
    public S2sJwtDecoder s2sJwtDecoder(S2sProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(props.getJwksUri()).build();
        decoder.setJwtValidator(s2sTokenValidator(props.getIssuer(), props.getExpectedAudience()));
        return new S2sJwtDecoder(decoder);
    }

    /** Package-private and static so tests can check it against decoded tokens without a JWKS endpoint. */
    static OAuth2TokenValidator<Jwt> s2sTokenValidator(String issuer, String expectedAudience) {
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        // The default validators already check iss; the explicit one keeps the pin unmistakable.
        validators.add(JwtValidators.createDefaultWithIssuer(issuer));
        validators.add(new JwtIssuerValidator(issuer));
        if (expectedAudience != null && !expectedAudience.isBlank()) {
            validators.add(new RequiredAudienceValidator(expectedAudience));
        }
        return new DelegatingOAuth2TokenValidator<>(validators);
    }

    /** {@link Jwt#getAudience()} accepts a string or an array {@code aud}; Keycloak emits an array. */
    static final class RequiredAudienceValidator implements OAuth2TokenValidator<Jwt> {

        private static final OAuth2Error INVALID_AUDIENCE = new OAuth2Error(
                "invalid_token",
                "The required audience is missing",
                "https://datatracker.ietf.org/doc/html/rfc6750#section-3.1");

        private final String expectedAudience;

        RequiredAudienceValidator(String expectedAudience) {
            this.expectedAudience = expectedAudience;
        }

        @Override
        public OAuth2TokenValidatorResult validate(Jwt jwt) {
            List<String> audience = jwt.getAudience();
            if (audience != null && audience.contains(expectedAudience)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(INVALID_AUDIENCE);
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public S2sAuthClientInterceptor s2sAuthClientInterceptor(S2sTokenProvider provider,
                                                             ApplicationEventPublisher events) {
        return new S2sAuthClientInterceptor(provider, events);
    }

    @Bean
    @ConditionalOnMissingBean
    public S2sScopeRegistry s2sScopeRegistry(java.util.List<BindableService> services) {
        return new S2sScopeRegistry(services);
    }

    @Bean
    @ConditionalOnMissingBean
    public S2sAuthServerInterceptor s2sAuthServerInterceptor(
            S2sJwtDecoder s2sJwtDecoder,
            S2sProperties props,
            ApplicationEventPublisher events,
            S2sScopeRegistry scopeRegistry,
            ObjectProvider<MeterRegistry> meterRegistry) {
        return new S2sAuthServerInterceptor(s2sJwtDecoder::decode, props, events, scopeRegistry,
                meterRegistry.getIfAvailable());
    }

    /**
     * An empty azp allow-list leaves the plane open; announcing the policy makes that read as a decision
     * in the logs, not as a missing property.
     */
    @Bean
    public ApplicationListener<ContextRefreshedEvent> s2sAzpPolicyAnnouncement(S2sProperties props,
                                                                              ApplicationEventPublisher events) {
        S2sAuthEvents.InboundAzpPolicy policy =
                new S2sAuthEvents.InboundAzpPolicy(S2sAzpAllowList.from(props).clientIds());
        // A child context (e.g. a separate management port) refreshes too and its event reaches us.
        AtomicBoolean announced = new AtomicBoolean();
        return refreshed -> {
            if (announced.compareAndSet(false, true)) {
                events.publishEvent(policy);
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ecclesiaflow.platform.rpc.logging", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public PlatformRpcLoggingAspect platformRpcLoggingAspect() {
        return new PlatformRpcLoggingAspect();
    }

    // gRPC calls bypass Spring proxies, so they are logged from events rather than by the aspect.
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ecclesiaflow.platform.rpc.logging", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public S2sAuthEventListener s2sAuthEventListener() {
        return new S2sAuthEventListener();
    }
}
