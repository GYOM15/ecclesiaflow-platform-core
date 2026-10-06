package com.ecclesiaflow.platform.security.autoconfigure;

import org.springframework.boot.actuate.autoconfigure.web.server.ConditionalOnManagementPort;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.List;
import java.util.stream.Stream;

/**
 * Opens the scrape and the probes of the management port to tokenless internal callers (Prometheus, the
 * orchestrator) and refuses every other actuator endpoint; the unpublished port is the boundary.
 *
 * <p>Ordered first because the child context of a separate management port reuses the application's
 * chains, whose {@code anyRequest().authenticated()} would answer the scrape 401. It has no login
 * mechanism, so an endpoint off the anonymous list is refused to everyone. Loaded only while the
 * management port differs: on a shared port the permit-all would reach the edge. A module bean named
 * {@code managementSecurityFilterChain} takes precedence.</p>
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration",
        "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration"})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = {
        "org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType",
        "org.springframework.security.config.annotation.web.builders.HttpSecurity",
        "org.springframework.security.web.SecurityFilterChain"})
@ConditionalOnManagementPort(ManagementPortType.DIFFERENT)
@ConditionalOnProperty(prefix = "ecclesiaflow.platform.management.security", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class PlatformManagementSecurityAutoConfiguration {

    static final String CHAIN = "managementSecurityFilterChain";

    static final String ACTUATOR_BASE = "/actuator";

    /** Each id added here is served without authentication to every container on the internal network. */
    static final List<String> ANONYMOUS_ENDPOINTS = List.of("health", "info", "prometheus");

    @Bean(CHAIN)
    @ConditionalOnMissingBean(name = CHAIN)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain managementSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher(new AntPathRequestMatcher(ACTUATOR_BASE + "/**"))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(anonymousPaths()).permitAll()
                        .anyRequest().authenticated())
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .requestCache(RequestCacheConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }

    // Paths, not EndpointRequest: endpoint ids resolve in the child context, which this chain, built
    // in the parent, cannot see, so the matcher would claim nothing. Ant matchers, not strings: with
    // two dispatcher servlets a string becomes an MVC matcher that fails at request time.
    private static RequestMatcher[] anonymousPaths() {
        return ANONYMOUS_ENDPOINTS.stream()
                .flatMap(id -> "health".equals(id)
                        ? Stream.of(ACTUATOR_BASE + "/health", ACTUATOR_BASE + "/health/**")
                        : Stream.of(ACTUATOR_BASE + "/" + id))
                .map(AntPathRequestMatcher::new)
                .toArray(RequestMatcher[]::new);
    }
}
