package com.ecclesiaflow.platform.ratelimit.autoconfigure;

import com.ecclesiaflow.platform.ratelimit.RateLimitInterceptor;
import com.ecclesiaflow.platform.ratelimit.RateLimitRuleRegistry;
import com.ecclesiaflow.platform.ratelimit.RateLimitSubjectResolver;
import com.ecclesiaflow.platform.ratelimit.RateLimiter;
import com.ecclesiaflow.platform.ratelimit.RedisRateLimiter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires the limiter when — and only when — a module actually asks for one.
 *
 * <p>Three conditions, each removing a way to get this wrong:
 * <ul>
 *   <li>the classes must be on the path, so a module that carries neither Redis
 *       nor Spring MVC is untouched;</li>
 *   <li>the module must supply both a {@link RateLimitRuleRegistry} and a
 *       {@link RateLimitSubjectResolver}. Without them there is nothing to
 *       enforce and nobody to count, so half-wiring is impossible;</li>
 *   <li>{@code ecclesiaflow.rate-limit.enabled} may switch it off — for a test
 *       that has no Redis, never for production convenience.</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnClass({StringRedisTemplate.class, WebMvcConfigurer.class})
@ConditionalOnProperty(prefix = "ecclesiaflow.rate-limit", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class PlatformRateLimitAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(StringRedisTemplate.class)
    public RateLimiter ecclesiaflowRateLimiter(StringRedisTemplate redis) {
        return new RedisRateLimiter(redis);
    }

    /**
     * Registers the interceptor only once BOTH module-supplied beans exist.
     * Missing either one leaves the application running with no limiter rather
     * than with a broken one.
     */
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
