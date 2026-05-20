package com.ecclesiaflow.platform.rpc.autoconfigure;

import com.ecclesiaflow.platform.rpc.events.S2sAuthEventListener;
import com.ecclesiaflow.platform.rpc.logging.PlatformRpcLoggingAspect;
import com.ecclesiaflow.platform.rpc.s2s.interceptor.S2sAuthClientInterceptor;
import com.ecclesiaflow.platform.rpc.s2s.interceptor.S2sAuthServerInterceptor;
import com.ecclesiaflow.platform.rpc.s2s.S2sProperties;
import com.ecclesiaflow.platform.rpc.s2s.token.S2sTokenCache;
import com.ecclesiaflow.platform.rpc.s2s.token.S2sTokenClient;
import com.ecclesiaflow.platform.rpc.s2s.token.S2sTokenProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Spring Boot auto-configuration for EcclesiaFlow's platform RPC library.
 *
 * <p>Activates only when {@code ecclesiaflow.platform.rpc.s2s.client-id} is set
 * — consumers that don't need s2s auth (e.g. tests or local-only development)
 * can leave it unset and the library stays inert.</p>
 *
 * <p>All beans are registered with {@link ConditionalOnMissingBean} so any
 * consumer can override individual pieces (typically the {@link JwtDecoder}
 * if they already have one configured for their REST API).</p>
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
     * Default {@link JwtDecoder} that fetches Keycloak's signing keys from the
     * configured JWKS URI. If the consumer already has a {@code JwtDecoder} bean
     * (typically because they configured Spring Security OAuth2 Resource Server
     * for their REST API), this one is skipped and the existing decoder is reused.
     */
    @Bean
    @ConditionalOnMissingBean
    public JwtDecoder platformRpcJwtDecoder(S2sProperties props) {
        return NimbusJwtDecoder.withJwkSetUri(props.getJwksUri()).build();
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

    @Bean
    @ConditionalOnMissingBean
    public S2sAuthServerInterceptor s2sAuthServerInterceptor(JwtDecoder jwtDecoder,
                                                             S2sProperties props,
                                                             ApplicationEventPublisher events) {
        return new S2sAuthServerInterceptor(jwtDecoder, props, events);
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
