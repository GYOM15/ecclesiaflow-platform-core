package com.ecclesiaflow.platform.ratelimit.autoconfigure;

import com.ecclesiaflow.platform.ratelimit.RateLimitInterceptor;
import com.ecclesiaflow.platform.ratelimit.RateLimitRuleRegistry;
import com.ecclesiaflow.platform.ratelimit.RateLimitSubjectResolver;
import com.ecclesiaflow.platform.ratelimit.RateLimiter;
import com.ecclesiaflow.platform.ratelimit.RedisRateLimiter;
import com.ecclesiaflow.platform.ratelimit.events.RateLimitEventListener;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Ordered after Redis: {@code @ConditionalOnBean} is evaluated when this class is processed, and before
 * RedisAutoConfiguration no StringRedisTemplate exists, so no RateLimiter would ever be created.
 * {@code ecclesiaflow.rate-limit.enabled=false} is for tests without Redis, never for production.
 */
@AutoConfiguration(after = RedisAutoConfiguration.class)
@ConditionalOnClass({StringRedisTemplate.class, WebMvcConfigurer.class})
@ConditionalOnProperty(prefix = "ecclesiaflow.rate-limit", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class PlatformRateLimitAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(StringRedisTemplate.class)
    public RateLimiter ecclesiaflowRateLimiter(StringRedisTemplate redis, ApplicationEventPublisher events) {
        return new RedisRateLimiter(redis, events);
    }

    @Bean
    @ConditionalOnMissingBean
    public RateLimitEventListener rateLimitEventListener() {
        return new RateLimitEventListener();
    }

    /** Without the module's registry and resolver there is nothing to enforce: no interceptor, not a broken one. */
    @Bean
    @ConditionalOnBean({RateLimiter.class, RateLimitRuleRegistry.class, RateLimitSubjectResolver.class})
    public WebMvcConfigurer ecclesiaflowRateLimitWebMvcConfigurer(
            RateLimiter limiter,
            RateLimitRuleRegistry registry,
            RateLimitSubjectResolver subjects) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry interceptors) {
                interceptors.addInterceptor(new RateLimitInterceptor(limiter, registry, subjects));
            }
        };
    }
}
