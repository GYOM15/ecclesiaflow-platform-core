package com.ecclesiaflow.platform.security.autoconfigure;

import com.ecclesiaflow.platform.contract.ApiContract;
import com.ecclesiaflow.platform.contract.autoconfigure.PlatformApiContractAutoConfiguration;
import com.ecclesiaflow.platform.security.surface.Surface;
import com.ecclesiaflow.platform.security.surface.SurfaceAuthorizationFilter;
import com.ecclesiaflow.platform.security.surface.SurfaceIndex;
import com.ecclesiaflow.platform.security.surface.SurfaceProperties;
import com.ecclesiaflow.platform.security.surface.SurfaceRouteVerifier;
import com.ecclesiaflow.platform.security.surface.events.SurfaceAuthorizationEventListener;
import com.ecclesiaflow.platform.security.surface.events.SurfaceAuthorizationEvents;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.security.web.access.WebInvocationPrivilegeEvaluator;
import org.springframework.web.servlet.handler.AbstractUrlHandlerMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Applies each operation's {@code x-ef-surface} to token-bearing requests, from the module's OpenAPI contract,
 * and refuses to start a module whose security chain opens a route the contract does not declare
 * {@code security: []}, or closes one it does.
 *
 * <p>Closed by default: no property turns it off, the mode is {@code enforce} unless
 * {@code ecclesiaflow.security.surface.mode=log-only} is set, and a module that adopts it without a contract
 * does not start. The only way out is excluding this auto-configuration, which shows in the module's own code.
 * The filter beans are not {@code @ConditionalOnMissingBean} for the same reason: a module bean of the same
 * type must not silently replace enforcement.</p>
 */
@AutoConfiguration(after = PlatformApiContractAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = {
        "org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken",
        "org.springframework.security.web.access.WebInvocationPrivilegeEvaluator",
        "org.springframework.web.servlet.DispatcherServlet"})
@EnableConfigurationProperties(SurfaceProperties.class)
public class SurfaceScopeAuthorizationAutoConfiguration {

    static final String MODE_METRIC = "surface.mode";

    @Bean
    public SurfaceIndex surfaceIndex(ApiContract contract) {
        return new SurfaceIndex(contract);
    }

    /** Right after Spring Security's chain: the token is authenticated, and a refusal still reaches its handler. */
    @Bean
    public FilterRegistrationBean<SurfaceAuthorizationFilter> surfaceAuthorizationFilter(
            SurfaceIndex surfaces,
            SurfaceProperties properties,
            ApplicationEventPublisher events,
            ObjectProvider<MeterRegistry> meterRegistry,
            ObjectProvider<SecurityProperties> security) {
        SurfaceAuthorizationFilter filter = new SurfaceAuthorizationFilter(surfaces, properties.getMode(), events,
                meterRegistry.getIfAvailable());
        FilterRegistrationBean<SurfaceAuthorizationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setName("surfaceAuthorizationFilter");
        registration.setOrder(security.getIfAvailable(SecurityProperties::new).getFilter().getOrder() + 1);
        return registration;
    }

    @Bean
    public SurfaceRouteVerifier surfaceRouteVerifier(
            ApiContract contract,
            ObjectProvider<WebInvocationPrivilegeEvaluator> privileges,
            ObjectProvider<RequestMappingInfoHandlerMapping> handlerMethodMappings,
            ObjectProvider<AbstractUrlHandlerMapping> urlMappings) {
        return new SurfaceRouteVerifier(contract, privileges, handlerMethodMappings, urlMappings);
    }

    @Bean
    @ConditionalOnMissingBean
    public SurfaceAuthorizationEventListener surfaceAuthorizationEventListener() {
        return new SurfaceAuthorizationEventListener();
    }

    @Bean
    public ApplicationListener<ContextRefreshedEvent> surfaceModeAnnouncement(SurfaceIndex surfaces,
                                                                              SurfaceProperties properties,
                                                                              ApplicationEventPublisher events) {
        SurfaceAuthorizationEvents.ModeActive active = new SurfaceAuthorizationEvents.ModeActive(
                properties.getMode(),
                surfaces.count(Surface.MEMBER),
                surfaces.count(Surface.CONSOLE),
                surfaces.count(Surface.PLATFORM),
                surfaces.tokenlessCount());
        // A child context (a separate management port) refreshes too and its event reaches us.
        AtomicBoolean announced = new AtomicBoolean();
        return refreshed -> {
            if (announced.compareAndSet(false, true)) {
                events.publishEvent(active);
            }
        };
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    static class SurfaceModeMetricConfiguration {

        /** Exported to Prometheus as {@code surface_mode{mode="enforce"}} or {@code {mode="log-only"}}, value 1. */
        @Bean
        public MeterBinder surfaceModeMeterBinder(SurfaceProperties properties) {
            return registry -> Gauge.builder(MODE_METRIC, () -> 1)
                    .description("The surface authorization mode in force")
                    .tag("mode", properties.getMode().tag())
                    .register(registry);
        }
    }
}
