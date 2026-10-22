package com.ecclesiaflow.platform.security.reauth;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Duration;
import java.util.Arrays;

/**
 * Applies {@link RecentAuthRequired}. Lowest precedence, and registered by an auto-configuration, hence after the
 * module's own aspects of the same order: its access checks refuse a caller before a confirmation link is sent.
 *
 * <p>The policy and the issuer are looked up per call, not injected: aspects are built before most beans, and the
 * issuer belongs to the module's business layer. {@link RecentAuthVerifier} has already refused to start a module
 * that annotates a method without them.</p>
 */
@Aspect
@Order(Ordered.LOWEST_PRECEDENCE)
public class RecentAuthAspect {

    private final ObjectProvider<RecentAuthPolicy> policy;
    private final ObjectProvider<PendingConfirmationIssuer> issuer;

    public RecentAuthAspect(ObjectProvider<RecentAuthPolicy> policy, ObjectProvider<PendingConfirmationIssuer> issuer) {
        this.policy = policy;
        this.issuer = issuer;
    }

    @Around(value = "@annotation(recentAuth)", argNames = "call,recentAuth")
    public Object requireRecentAuth(ProceedingJoinPoint call, RecentAuthRequired recentAuth) throws Throwable {
        RecentAuthVerdict verdict = policy.getObject().require(Duration.ofSeconds(recentAuth.maxAgeSeconds()));
        if (verdict == RecentAuthVerdict.PASSED) {
            return call.proceed();
        }
        SensitiveAction action = new SensitiveAction(recentAuth.action(), subject(), Arrays.asList(call.getArgs()));
        Object body = issuer.getObject().issue(action);
        if (body == null) {
            throw new IllegalStateException(
                    "The PendingConfirmationIssuer returned no body for " + recentAuth.action());
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    // The policy only answers CONFIRM_OUT_OF_BAND for an authenticated token that names its subject.
    private static String subject() {
        JwtAuthenticationToken token = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        return token.getToken().getSubject();
    }
}
