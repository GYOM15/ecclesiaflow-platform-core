package com.ecclesiaflow.platform.web.trace.autoconfigure;

import com.ecclesiaflow.platform.web.trace.TraceparentFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

/** Servlet applications only; {@code ecclesiaflow.platform.web.traceparent.enabled=false} removes the filter. */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "org.springframework.web.filter.OncePerRequestFilter")
@ConditionalOnProperty(prefix = "ecclesiaflow.platform.web.traceparent", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class PlatformTraceparentAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public FilterRegistrationBean<TraceparentFilter> traceparentFilter() {
        FilterRegistrationBean<TraceparentFilter> registration = new FilterRegistrationBean<>(new TraceparentFilter());
        registration.setName("traceparentFilter");
        registration.setOrder(TraceparentFilter.ORDER);
        return registration;
    }
}
