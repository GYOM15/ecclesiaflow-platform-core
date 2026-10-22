package com.ecclesiaflow.platform.security.autoconfigure;

import com.ecclesiaflow.platform.security.RestJwtProperties;
import com.ecclesiaflow.platform.security.jwks.FaultTolerantJwkSource;
import com.ecclesiaflow.platform.security.jwks.events.JwksEventListener;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.util.StringUtils;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Registered ahead of Spring Boot's resource-server decoder, which pins the issuer but checks no
 * audience; a module that declares its own {@link JwtDecoder} keeps it. The default audience,
 * {@code ecclesiaflow-internal}, keeps either deploy order safe. The target, {@code ecclesiaflow-app},
 * which only end-user clients carry, is set once the realm stamps it, never as the default: that would
 * refuse every signed-in user while the realm lags behind.
 *
 * <p>The keys come from a {@link FaultTolerantJwkSource}, so a Keycloak outage keeps valid tokens decoding.</p>
 */
@AutoConfiguration(before = OAuth2ResourceServerAutoConfiguration.class)
@EnableConfigurationProperties({RestJwtProperties.class, OAuth2ResourceServerProperties.class})
@ConditionalOnProperty(prefix = "spring.security.oauth2.resourceserver.jwt", name = "jwk-set-uri")
@ConditionalOnProperty(prefix = "ecclesiaflow.rest.jwt", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class PlatformRestJwtDecoderAutoConfiguration implements ApplicationEventPublisherAware {

    static final String ISSUER_PROPERTY = "spring.security.oauth2.resourceserver.jwt.issuer-uri";

    // nimbus's own bound: a key set is a few kilobytes, and an endless body must not fill the heap.
    private static final int JWK_SET_SIZE_LIMIT = 50 * 1024;

    private ApplicationEventPublisher events = event -> { };

    // Through the Aware callback, so that restJwtDecoder keeps its public signature.
    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.events = applicationEventPublisher;
    }

    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    public JwtDecoder restJwtDecoder(OAuth2ResourceServerProperties resourceServer, RestJwtProperties rest) {
        OAuth2ResourceServerProperties.Jwt jwt = resourceServer.getJwt();
        if (!StringUtils.hasText(jwt.getIssuerUri())) {
            throw new IllegalStateException(ISSUER_PROPERTY + " must be set: the REST decoder pins the issuer");
        }
        FaultTolerantJwkSource keys = jwkSource(jwt.getJwkSetUri(), rest.getJwks(), Clock.systemUTC(), events);
        // Spring's builder keeps its header and claim rules; only where the keys come from changes. RS256 is
        // the algorithm the builder pins when none is configured, and the one Keycloak signs access tokens with.
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwt.getJwkSetUri())
                .jwtProcessorCustomizer(processor -> processor.setJWSKeySelector(
                        new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys)))
                .build();
        decoder.setJwtValidator(restTokenValidator(jwt.getIssuerUri(), rest.getAudience()));
        return decoder;
    }

    @Bean
    @ConditionalOnMissingBean
    public JwksEventListener jwksEventListener() {
        return new JwksEventListener();
    }

    static FaultTolerantJwkSource jwkSource(String jwkSetUri, RestJwtProperties.Jwks settings, Clock clock,
                                            ApplicationEventPublisher events) {
        DefaultResourceRetriever retriever = new DefaultResourceRetriever(
                timeoutMillis(settings.getConnectTimeout(), "connect-timeout"),
                timeoutMillis(settings.getReadTimeout(), "read-timeout"),
                JWK_SET_SIZE_LIMIT);
        retriever.setHeaders(Map.of("Accept", List.of("application/json, application/jwk-set+json")));
        return new FaultTolerantJwkSource(url(jwkSetUri), retriever, settings, clock, events);
    }

    // Zero means no timeout to HttpURLConnection: a hung Keycloak would then hold requests forever.
    private static int timeoutMillis(Duration timeout, String name) {
        if (timeout == null || timeout.toMillis() <= 0 || timeout.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "ecclesiaflow.rest.jwt.jwks." + name + " must be a positive duration, was " + timeout);
        }
        return (int) timeout.toMillis();
    }

    private static URL url(String jwkSetUri) {
        try {
            return URI.create(jwkSetUri).toURL();
        } catch (IllegalArgumentException | MalformedURLException e) {
            throw new IllegalStateException("spring.security.oauth2.resourceserver.jwt.jwk-set-uri is not a URL", e);
        }
    }

    static OAuth2TokenValidator<Jwt> restTokenValidator(String issuer, String expectedAudience) {
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(JwtValidators.createDefaultWithIssuer(issuer));
        validators.add(new JwtIssuerValidator(issuer));
        if (StringUtils.hasText(expectedAudience)) {
            validators.add(requiredAudience(expectedAudience));
        }
        return new DelegatingOAuth2TokenValidator<>(validators);
    }

    // The expected value is named, the token's own audiences are not: a 401 cannot be used to list
    // what the realm issues.
    private static OAuth2TokenValidator<Jwt> requiredAudience(String expected) {
        OAuth2Error missing = new OAuth2Error("invalid_token", "The required audience " + expected + " is missing", null);
        return jwt -> {
            List<String> audiences = jwt.getAudience();
            return audiences != null && audiences.contains(expected)
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(missing);
        };
    }
}
