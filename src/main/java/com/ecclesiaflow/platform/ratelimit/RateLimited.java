package com.ecclesiaflow.platform.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method as rate limited, naming the rule that applies.
 *
 * <p>On the METHOD rather than in a list of URI patterns: a limit that lives
 * beside the operation it protects survives a route being renamed, and a reader
 * of the controller can see that the operation is limited without going to look
 * for a configuration file. The billing module's first limiter matched on URI
 * suffixes and would have silently stopped applying the day a path changed.
 *
 * <p>The rule itself — how many, over how long, counted against whom — is
 * declared once per module in a {@link RateLimitRuleRegistry}, so the numbers
 * are all in one place and can be read as a policy rather than hunted for
 * across annotations.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimited {

    /** The rule's name, as registered in this module's {@link RateLimitRuleRegistry}. */
    String value();
}
