package com.ecclesiaflow.platform.security.reauth;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * Applies {@link RecentAuthRequired}. Lowest precedence, and registered by an auto-configuration, hence after the
 * module's own aspects of the same order: its access checks refuse a caller before a link or a code is sent.
 *
 * <p>The gate is looked up per call, not injected: aspects are built before most beans.</p>
 */
@Aspect
@Order(Ordered.LOWEST_PRECEDENCE)
public class RecentAuthAspect {

    private final ObjectProvider<RecentAuthGate> gate;

    public RecentAuthAspect(ObjectProvider<RecentAuthGate> gate) {
        this.gate = gate;
    }

    @Around(value = "@annotation(recentAuth)", argNames = "call,recentAuth")
    public Object requireRecentAuth(ProceedingJoinPoint call, RecentAuthRequired recentAuth) throws Throwable {
        Optional<Object> pending = gate.getObject().hold(recentAuth.action(),
                Duration.ofSeconds(recentAuth.maxAgeSeconds()), recentAuth.linkedAccounts(),
                Arrays.asList(call.getArgs()));
        if (pending.isEmpty()) {
            return call.proceed();
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(pending.get());
    }
}
