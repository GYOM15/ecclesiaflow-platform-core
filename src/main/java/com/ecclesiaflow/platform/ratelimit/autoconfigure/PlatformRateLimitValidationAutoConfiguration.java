package com.ecclesiaflow.platform.ratelimit.autoconfigure;

import com.ecclesiaflow.platform.ratelimit.RateLimitRuleRegistry;
import com.ecclesiaflow.platform.ratelimit.RateLimitSubjectResolver;
import com.ecclesiaflow.platform.ratelimit.RateLimitedHandlerValidator;
import com.ecclesiaflow.platform.ratelimit.RateLimiter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Startup check for {@code @RateLimited} handlers, kept apart from
 * {@link PlatformRateLimitAutoConfiguration}: it must still run when that one backs off
 * because Redis is missing, which is exactly when the limit would silently not apply.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(RequestMappingHandlerMapping.class)
@ConditionalOnProperty(prefix = "ecclesiaflow.rate-limit", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class PlatformRateLimitValidationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RateLimitedHandlerValidator rateLimitedHandlerValidator(
            ObjectProvider<RequestMappingHandlerMapping> handlerMappings,
            ObjectProvider<RateLimiter> limiter,
            ObjectProvider<RateLimitRuleRegistry> registry,
            ObjectProvider<RateLimitSubjectResolver> subjects) {
        return new RateLimitedHandlerValidator(handlerMappings, limiter, registry, subjects);
    }
}
