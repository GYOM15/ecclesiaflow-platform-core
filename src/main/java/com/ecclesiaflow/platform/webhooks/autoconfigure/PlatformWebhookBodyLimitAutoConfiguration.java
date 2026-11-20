package com.ecclesiaflow.platform.webhooks.autoconfigure;

import com.ecclesiaflow.platform.webhooks.web.WebhookBodyLimitFilter;
import com.ecclesiaflow.platform.webhooks.web.WebhookBodyLimitProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/** Once enabled, a missing or malformed route list fails the startup rather than leave a route unbounded. */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "org.springframework.web.filter.OncePerRequestFilter")
@ConditionalOnProperty(prefix = "ecclesiaflow.webhooks.body-limit", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(WebhookBodyLimitProperties.class)
public class PlatformWebhookBodyLimitAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public FilterRegistrationBean<WebhookBodyLimitFilter> webhookBodyLimitFilter(WebhookBodyLimitProperties properties,
                                                                                ObjectProvider<Clock> clock) {
        WebhookBodyLimitFilter filter = new WebhookBodyLimitFilter(properties.getPathPrefixes(),
                properties.getMaxBytes(), clock.getIfUnique(Clock::systemUTC));
        FilterRegistrationBean<WebhookBodyLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setName("webhookBodyLimitFilter");
        registration.setOrder(WebhookBodyLimitFilter.ORDER);
        return registration;
    }
}
