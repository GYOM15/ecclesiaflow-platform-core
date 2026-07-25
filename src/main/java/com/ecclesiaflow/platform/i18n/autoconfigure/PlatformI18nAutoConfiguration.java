package com.ecclesiaflow.platform.i18n.autoconfigure;

import com.ecclesiaflow.platform.i18n.EffectiveLocaleResolver;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot auto-configuration for platform-core i18n. Exposes the stateless
 * {@link EffectiveLocaleResolver} so any module pulling in platform-core picks
 * it up without an explicit {@code @Import} or component-scan extension.
 *
 * <p>It deliberately does NOT contribute a Spring MVC {@code LocaleResolver}:
 * that decides how a module reads {@code Accept-Language}, and platform-core
 * does not depend on spring-webmvc. Each module wires its own bounded
 * {@code AcceptHeaderLocaleResolver} (supported {@code [fr, en]}, default
 * {@code fr}) in its own change, alongside the golden test that proves the
 * default rendering stays French.</p>
 */
@AutoConfiguration
public class PlatformI18nAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public EffectiveLocaleResolver effectiveLocaleResolver() {
        return new EffectiveLocaleResolver();
    }
}
