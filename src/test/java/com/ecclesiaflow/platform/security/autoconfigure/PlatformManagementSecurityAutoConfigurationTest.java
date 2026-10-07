package com.ecclesiaflow.platform.security.autoconfigure;

import jakarta.servlet.Filter;
import jakarta.servlet.ServletContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.context.WebApplicationContext;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requests go through the real filter chains: the module's, which wants a token everywhere, and the
 * shared management chain, which must open the scrape and the probes and nothing else.
 */
class PlatformManagementSecurityAutoConfigurationTest {

    private static final String CHAIN = "managementSecurityFilterChain";

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformManagementSecurityAutoConfiguration.class))
            .withUserConfiguration(ApplicationSecurity.class)
            .withPropertyValues("server.port=8080", "management.server.port=9097");

    /** A module's own chain, as each one declares it: a token for everything. */
    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class ApplicationSecurity {

        @Bean
        SecurityFilterChain applicationSecurityFilterChain(HttpSecurity http) throws Exception {
            return http
                    .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                    .exceptionHandling(errors -> errors.authenticationEntryPoint(
                            new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                    .build();
        }
    }

    /** A module that still carries its own copy of the management chain. */
    @Configuration(proxyBeanMethods = false)
    static class ModuleManagementChain {

        @Bean(CHAIN)
        @Order(Ordered.HIGHEST_PRECEDENCE)
        SecurityFilterChain managementSecurityFilterChain(HttpSecurity http) throws Exception {
            return http.securityMatcher(new AntPathRequestMatcher("/module-actuator/**"))
                    .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                    .build();
        }
    }

    record Outcome(int status, boolean reachedEndpoint, boolean sessionCreated) {
    }

    private static Outcome get(WebApplicationContext context, String path) throws Exception {
        Filter security = context.getBean("springSecurityFilterChain", Filter.class);
        // Spring Boot's own matchers find the context through the servlet context, as a server sets it up.
        ServletContext servletContext = context.getServletContext();
        servletContext.setAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE, context);
        MockHttpServletRequest request = new MockHttpServletRequest(servletContext, "GET", path);
        request.setServletPath(path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        security.doFilter(request, response, (req, res) -> reached.set(true));

        return new Outcome(response.getStatus(), reached.get(), request.getSession(false) != null);
    }

    @Nested
    @DisplayName("on a separate management port")
    class SeparatePort {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"/actuator/prometheus", "/actuator/health", "/actuator/health/readiness",
                "/actuator/health/liveness", "/actuator/info"})
        @DisplayName("opens the scrape and the probes to a caller with no token, without a session")
        void anonymousEndpoints(String path) {
            runner.run(context -> {
                Outcome outcome = get(context, path);

                assertThat(outcome.reachedEndpoint()).isTrue();
                assertThat(outcome.sessionCreated()).isFalse();
            });
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"/actuator/metrics", "/actuator/metrics/jvm.memory.used", "/actuator/env",
                "/actuator/loggers", "/actuator/healthz", "/actuator"})
        @DisplayName("refuses every other actuator endpoint")
        void otherEndpoints(String path) {
            runner.run(context -> {
                Outcome outcome = get(context, path);

                assertThat(outcome.reachedEndpoint()).isFalse();
                assertThat(outcome.status()).isEqualTo(HttpStatus.FORBIDDEN.value());
            });
        }

        @Test
        @DisplayName("leaves every other path to the module's chain")
        void applicationPaths() {
            runner.run(context -> {
                Outcome outcome = get(context, "/members/me");

                assertThat(outcome.reachedEndpoint()).isFalse();
                assertThat(outcome.status()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            });
        }

        @Test
        @DisplayName("gives way to a module's own chain of the same name")
        void moduleChainWins() {
            runner.withUserConfiguration(ModuleManagementChain.class).run(context -> {
                assertThat(get(context, "/actuator/prometheus").status()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
                assertThat(get(context, "/module-actuator/anything").reachedEndpoint()).isTrue();
            });
        }

        @Test
        @DisplayName("stays out of a module without a chain of its own: Spring Boot's defaults keep guarding it")
        void bootDefaultsWithoutModuleChain() {
            new WebApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(WebMvcAutoConfiguration.class,
                            SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class,
                            ManagementWebSecurityAutoConfiguration.class,
                            PlatformManagementSecurityAutoConfiguration.class))
                    .withPropertyValues("server.port=8080", "management.server.port=9097")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(get(context, "/members/me").status()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
                        assertThat(get(context, "/actuator/prometheus").status())
                                .isEqualTo(HttpStatus.UNAUTHORIZED.value());
                    });
        }

        @Test
        @DisplayName("is turned off by its property")
        void disabled() {
            runner.withPropertyValues("ecclesiaflow.platform.management.security.enabled=false").run(context -> {
                assertThat(context).doesNotHaveBean(CHAIN);
                assertThat(get(context, "/actuator/prometheus").status()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            });
        }
    }

    @Nested
    @DisplayName("without a separate management port")
    class SharedPort {

        @Test
        @DisplayName("is absent when the endpoints share the application's port: the module's chain answers")
        void samePort() {
            runner.withPropertyValues("management.server.port=8080").run(context -> {
                assertThat(context).doesNotHaveBean(CHAIN);
                assertThat(get(context, "/actuator/prometheus").status()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            });
        }

        @Test
        @DisplayName("is absent when no management port is set")
        void noManagementPort() {
            new WebApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(PlatformManagementSecurityAutoConfiguration.class))
                    .withUserConfiguration(ApplicationSecurity.class)
                    .withPropertyValues("server.port=8080")
                    .run(context -> assertThat(context).doesNotHaveBean(CHAIN));
        }

        @Test
        @DisplayName("is absent outside a servlet application")
        void notAServletApplication() {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(PlatformManagementSecurityAutoConfiguration.class))
                    .withPropertyValues("server.port=8080", "management.server.port=9097")
                    .run(context -> assertThat(context).doesNotHaveBean(CHAIN));
        }
    }

    @Test
    @DisplayName("is listed for auto-configuration, the path a consuming service takes")
    void registeredForAutoConfiguration() {
        assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()))
                .contains(PlatformManagementSecurityAutoConfiguration.class.getName());
    }
}
