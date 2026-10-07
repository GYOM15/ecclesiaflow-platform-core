package com.ecclesiaflow.platform.web.error.autoconfigure;

import com.ecclesiaflow.platform.error.ErrorCategoryResolver;
import com.ecclesiaflow.platform.error.autoconfigure.PlatformErrorAutoConfiguration;
import com.ecclesiaflow.platform.time.autoconfigure.PlatformClockAutoConfiguration;
import com.ecclesiaflow.platform.web.error.PlatformRestExceptionHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * Auto-configuration beans are registered after the service's own: that order keeps the service's
 * advice ahead of this one when both are lowest precedence.
 */
@AutoConfiguration(after = {PlatformErrorAutoConfiguration.class, PlatformClockAutoConfiguration.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
@ConditionalOnProperty(prefix = "ecclesiaflow.platform.web.errors", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class PlatformWebErrorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PlatformRestExceptionHandler platformRestExceptionHandler(ErrorCategoryResolver errorCategoryResolver,
                                                                     ObjectProvider<Clock> clock) {
        return new PlatformRestExceptionHandler(errorCategoryResolver, clock.getIfUnique(Clock::systemUTC)) {
        };
    }
}
