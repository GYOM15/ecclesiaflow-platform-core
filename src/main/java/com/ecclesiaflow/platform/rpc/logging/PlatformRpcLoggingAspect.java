package com.ecclesiaflow.platform.rpc.logging;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;

/**
 * AOP aspect dedicated to logging the platform RPC library's token operations.
 *
 * <p>Logs are kept out of the business code (single responsibility): the
 * {@code S2sTokenClient}, {@code S2sTokenCache} and {@code S2sTokenProvider}
 * classes throw or return values, and this aspect produces the operator-facing
 * messages. The gRPC interceptors — which gRPC calls directly, bypassing the
 * Spring proxy — use {@code ApplicationEventPublisher} instead and are handled
 * by {@link S2sAuthEventListener}.</p>
 *
 * <p>Activation is opt-out: drop the dependency or set
 * {@code ecclesiaflow.platform.rpc.logging.enabled=false} to silence the lib.</p>
 *
 * @author EcclesiaFlow Team
 * @since 0.1.0
 */
@Slf4j
@Aspect
public class PlatformRpcLoggingAspect {

    // ========================================================================
    // Pointcuts
    // ========================================================================

    /** All public methods of {@code S2sTokenClient} — the one and only HTTP exchange. */
    @Pointcut("execution(* com.ecclesiaflow.platform.rpc.s2s.token.S2sTokenClient.fetchToken(..))")
    public void tokenClientFetch() {}

    /** {@code S2sTokenProvider.getToken()} — outermost entry point for callers. */
    @Pointcut("execution(* com.ecclesiaflow.platform.rpc.s2s.token.S2sTokenProvider.getToken(..))")
    public void tokenProviderGetToken() {}

    // ========================================================================
    // Advices — token client (Keycloak network call)
    // ========================================================================

    @AfterReturning("tokenClientFetch()")
    public void logFetchSuccess(JoinPoint joinPoint) {
        log.info("S2S: ✅ Obtained fresh s2s token from Keycloak");
    }

    @AfterThrowing(pointcut = "tokenClientFetch()", throwing = "exception")
    public void logFetchFailure(JoinPoint joinPoint, Throwable exception) {
        log.error("S2S: ❌ Failed to obtain s2s token — {}: {}",
                exception.getClass().getSimpleName(),
                exception.getMessage());
    }

    // ========================================================================
    // Advices — provider (cache hits/misses are inferred from absence of fetch)
    // ========================================================================

    @AfterThrowing(pointcut = "tokenProviderGetToken()", throwing = "exception")
    public void logProviderFailure(JoinPoint joinPoint, Throwable exception) {
        // Distinct from the client-level message: this tells the operator
        // that a caller couldn't get a token, not just that the network call failed.
        log.warn("S2S: ❌ getToken() failed for caller — {}", exception.getMessage());
    }
}
