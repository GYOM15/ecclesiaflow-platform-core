package com.ecclesiaflow.platform.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * On the method rather than in URI patterns, so the limit survives a route rename and shows beside the
 * operation. The value names a rule of the module's {@link RateLimitRuleRegistry}.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimited {

    String value();
}
