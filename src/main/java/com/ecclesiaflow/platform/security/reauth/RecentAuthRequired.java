package com.ecclesiaflow.platform.security.reauth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A gesture that takes over an account if a stolen session can make it (closing the account, changing its
 * address, reactivating it). Applies to every token, web included, through {@link RecentAuthPolicy}:
 * <ul>
 *   <li>an account linked to an identity provider answers 202 with what the module's
 *       {@link PendingConfirmationIssuer} returns, and the method does not run: the provider can sign the user
 *       back in silently, so a fresh {@code auth_time} proves nothing for it;</li>
 *   <li>any other account runs the method only when its sign-in is at most {@link #maxAgeSeconds()} old and was
 *       not brokered by a provider, else 403 {@code REAUTH_REQUIRED};</li>
 *   <li>when the sign-in methods cannot be read, 503 {@code SERVICE_UNAVAILABLE}, never a pass.</li>
 * </ul>
 *
 * <p>On a public method of a bean, returning {@code ResponseEntity}, annotated on the class itself (an annotation
 * on an interface method is not intercepted); the module does not start otherwise. Called from inside its own
 * bean, the method bypasses the check. It runs after the module's other aspects of equal order, so an access
 * check that must refuse a caller before any confirmation is sent keeps the default order or a higher
 * precedence.</p>
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RecentAuthRequired {

    long DEFAULT_MAX_AGE_SECONDS = 300;

    /** Names the gesture for the {@link PendingConfirmationIssuer}, which resumes it once confirmed. */
    String action();

    /** 600 for a reactivation, which follows the sign-in that found the account deactivated. */
    long maxAgeSeconds() default DEFAULT_MAX_AGE_SECONDS;
}
