package com.ecclesiaflow.platform.security.autoconfigure;

import com.ecclesiaflow.platform.security.reauth.PendingConfirmationIssuer;
import com.ecclesiaflow.platform.security.reauth.RecentAuthAspect;
import com.ecclesiaflow.platform.security.reauth.RecentAuthExceptionHandler;
import com.ecclesiaflow.platform.security.reauth.RecentAuthGate;
import com.ecclesiaflow.platform.security.reauth.RecentAuthPolicy;
import com.ecclesiaflow.platform.security.reauth.RecentAuthVerifier;
import com.ecclesiaflow.platform.security.reauth.SignInMethodsClient;
import com.ecclesiaflow.platform.security.reauth.code.web.EmailCodeConfirmation;
import com.ecclesiaflow.platform.security.reauth.events.RecentAuthEventListener;
import com.ecclesiaflow.platform.security.reauth.events.RecentAuthMetrics;
import com.ecclesiaflow.platform.time.autoconfigure.PlatformClockAutoConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * {@link com.ecclesiaflow.platform.security.reauth.RecentAuthRequired} for servlet modules. The gate, the aspect and
 * the startup check are always on, with no property to turn them off and no {@code @ConditionalOnMissingBean}: a
 * module bean of the same type must not silently replace enforcement. The policy exists once the module declares
 * a {@link SignInMethodsClient}; a module that annotates a gesture without one, without a
 * {@link PendingConfirmationIssuer} for a gesture confirmed by link, or without the e-mail code
 * ({@code PlatformEmailCodeAutoConfiguration}) for one confirmed by code, does not start.
 */
@AutoConfiguration(after = PlatformClockAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = {
        "org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken",
        "org.springframework.web.servlet.DispatcherServlet",
        "org.aspectj.lang.annotation.Aspect"})
public class PlatformRecentAuthAutoConfiguration {

    @Bean
    @ConditionalOnBean(SignInMethodsClient.class)
    @ConditionalOnMissingBean
    public RecentAuthPolicy recentAuthPolicy(SignInMethodsClient signInMethodsClient, ObjectProvider<Clock> clock,
                                             ApplicationEventPublisher events) {
        return new RecentAuthPolicy(signInMethodsClient, clock.getIfUnique(Clock::systemUTC), events);
    }

    @Bean
    public RecentAuthGate recentAuthGate(ObjectProvider<RecentAuthPolicy> policy,
                                         ObjectProvider<PendingConfirmationIssuer> issuer,
                                         ObjectProvider<EmailCodeConfirmation> emailCodes) {
        return new RecentAuthGate(policy, issuer, emailCodes);
    }

    @Bean
    public RecentAuthAspect recentAuthAspect(ObjectProvider<RecentAuthGate> gate) {
        return new RecentAuthAspect(gate);
    }

    @Bean
    public RecentAuthVerifier recentAuthVerifier(ConfigurableListableBeanFactory beanFactory) {
        return new RecentAuthVerifier(beanFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    public RecentAuthEventListener recentAuthEventListener() {
        return new RecentAuthEventListener();
    }

    @Bean
    @ConditionalOnMissingBean
    public RecentAuthExceptionHandler recentAuthExceptionHandler(ObjectProvider<Clock> clock) {
        return new RecentAuthExceptionHandler(clock.getIfUnique(Clock::systemUTC)) {
        };
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    static class RecentAuthMetricsConfiguration {

        /** Without a registry bean the counter is inert rather than absent, so nothing else changes. */
        @Bean
        @ConditionalOnMissingBean
        RecentAuthMetrics recentAuthMetrics(ObjectProvider<MeterRegistry> registry) {
            return new RecentAuthMetrics(registry.getIfUnique());
        }
    }
}
