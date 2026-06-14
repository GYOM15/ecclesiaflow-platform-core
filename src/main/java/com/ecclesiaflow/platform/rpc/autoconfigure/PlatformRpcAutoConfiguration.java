package com.ecclesiaflow.platform.rpc.autoconfigure;

import com.ecclesiaflow.platform.rpc.events.S2sAuthEventListener;
import com.ecclesiaflow.platform.rpc.logging.PlatformRpcLoggingAspect;
import com.ecclesiaflow.platform.rpc.s2s.S2sProperties;
import com.ecclesiaflow.platform.rpc.s2s.interceptor.S2sAuthClientInterceptor;
import com.ecclesiaflow.platform.rpc.s2s.interceptor.S2sAuthServerInterceptor;
import com.ecclesiaflow.platform.rpc.s2s.interceptor.S2sScopeRegistry;
import com.ecclesiaflow.platform.rpc.s2s.token.S2sTokenCache;
import com.ecclesiaflow.platform.rpc.s2s.token.S2sTokenClient;
import com.ecclesiaflow.platform.rpc.s2s.token.S2sTokenProvider;
import io.grpc.BindableService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
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

import java.util.ArrayList;
import java.util.List;

/**
 * Spring Boot auto-configuration for EcclesiaFlow's platform RPC library.
 *
 * <p>Activates only when {@code ecclesiaflow.platform.rpc.s2s.client-id} is set
 * — consumers that don't need s2s auth (e.g. tests or local-only development)
 * can leave it unset and the library stays inert.</p>
 *
 * <p>Most beans are registered with {@link ConditionalOnMissingBean} so any
 * consumer can override individual pieces. The s2s {@link JwtDecoder} is the
 * deliberate exception: it is named {@code platformRpcJwtDecoder} and built
 * unconditionally with pinned issuer + audience validators, so a consumer's
 * REST-side {@code JwtDecoder} bean can never silently win bean resolution and
 * strip the s2s validation (this is what H02 guarded against). The server
 * interceptor injects this bean by name.</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(S2sProperties.class)
@ConditionalOnProperty(prefix = "ecclesiaflow.platform.rpc.s2s", name = "client-id")
public class PlatformRpcAutoConfiguration {
    // AOP is auto-activated by Spring Boot's AopAutoConfiguration as soon as
    // `spring-aspects` + `aspectjweaver` are on the classpath (they are, declared
    // in this lib's pom). No need to add @EnableAspectJAutoProxy here — it would
    // be redundant and would override the consumer's CGLIB/JDK-proxy choice.

    // ========================================================================
    // Token pipeline (SRP-decomposed: client → cache → provider)
    // ========================================================================

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

    // ========================================================================
    // JWT decoding
    // ========================================================================

    /**
     * S2s {@link JwtDecoder} that fetches Keycloak's signing keys from the
     * configured JWKS URI and validates, on top of signature + expiry:
     * <ul>
     *   <li>the {@code iss} claim (pinned to {@link S2sProperties#getIssuer()}), and</li>
     *   <li>the {@code aud} claim (must contain {@link S2sProperties#getExpectedAudience()},
     *       unless that property is left blank — the migration escape hatch).</li>
     * </ul>
     *
     * <p>Built unconditionally (no {@link ConditionalOnMissingBean}) and named
     * {@code platformRpcJwtDecoder} so it is deterministic: a consumer's REST-side
     * {@code JwtDecoder} can never displace it via bean ordering and quietly drop
     * the issuer/audience checks. Validation runs in the decoder, i.e. <em>before</em>
     * the interceptor extracts scopes.</p>
     */
    @Bean
    public JwtDecoder platformRpcJwtDecoder(S2sProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(props.getJwksUri()).build();
        decoder.setJwtValidator(s2sTokenValidator(props.getIssuer(), props.getExpectedAudience()));
        return decoder;
    }

    /**
     * Composite validator: default timestamp/format checks + a pinned issuer +
     * (unless the expected audience is blank) a required-audience check.
     *
     * <p>Package-visible and static so it can be unit-tested directly against
     * decoded {@link Jwt}s without standing up a JWKS endpoint.</p>
     *
     * @param issuer           expected {@code iss}; pinned via {@link JwtIssuerValidator}
     * @param expectedAudience required {@code aud} entry, or blank to skip the audience check
     */
    static OAuth2TokenValidator<Jwt> s2sTokenValidator(String issuer, String expectedAudience) {
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        // Default validators include the JwtTimestampValidator (exp/nbf) and,
        // since we pass an issuer, an issuer check — but we add an explicit
        // JwtIssuerValidator too so the pin is unmistakable and order-independent.
        validators.add(JwtValidators.createDefaultWithIssuer(issuer));
        validators.add(new JwtIssuerValidator(issuer));
        if (expectedAudience != null && !expectedAudience.isBlank()) {
            validators.add(new RequiredAudienceValidator(expectedAudience));
        }
        return new DelegatingOAuth2TokenValidator<>(validators);
    }

    /**
     * Rejects any token whose {@code aud} claim does not contain the expected
     * audience. The {@code aud} claim may be absent, a single string, or an
     * array (Keycloak emits an array); all shapes are handled.
     */
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

    // ========================================================================
    // gRPC interceptors
    // ========================================================================

    @Bean
    @ConditionalOnMissingBean
    public S2sAuthClientInterceptor s2sAuthClientInterceptor(S2sTokenProvider provider,
                                                             ApplicationEventPublisher events) {
        return new S2sAuthClientInterceptor(provider, events);
    }

    /**
     * Registry of per-RPC scope requirements declared via
     * {@code @S2sScopeRequired}. Spring injects every {@link BindableService}
     * bean on the classpath; the registry scans their public methods for
     * the annotation at startup.
     */
    @Bean
    @ConditionalOnMissingBean
    public S2sScopeRegistry s2sScopeRegistry(java.util.List<BindableService> services) {
        return new S2sScopeRegistry(services);
    }

    /**
     * The s2s decoder is injected by name via {@link Qualifier} so this
     * interceptor always gets {@code platformRpcJwtDecoder} (iss + aud pinned),
     * even when a consumer also exposes a REST-side {@link JwtDecoder} bean —
     * which would otherwise make the {@code JwtDecoder} dependency ambiguous now
     * that the s2s decoder is no longer {@code @ConditionalOnMissingBean}.
     */
    @Bean
    @ConditionalOnMissingBean
    public S2sAuthServerInterceptor s2sAuthServerInterceptor(
            @Qualifier("platformRpcJwtDecoder") JwtDecoder jwtDecoder,
            S2sProperties props,
            ApplicationEventPublisher events,
            S2sScopeRegistry scopeRegistry) {
        return new S2sAuthServerInterceptor(jwtDecoder, props, events, scopeRegistry);
    }

    // ========================================================================
    // Logging: aspect for Spring-managed token operations, listener for gRPC events
    //          (gRPC bypasses Spring's proxy, so AOP wouldn't fire there)
    // ========================================================================

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ecclesiaflow.platform.rpc.logging", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public PlatformRpcLoggingAspect platformRpcLoggingAspect() {
        return new PlatformRpcLoggingAspect();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ecclesiaflow.platform.rpc.logging", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public S2sAuthEventListener s2sAuthEventListener() {
        return new S2sAuthEventListener();
    }
}
