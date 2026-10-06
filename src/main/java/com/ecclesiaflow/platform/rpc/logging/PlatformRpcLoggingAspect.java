package com.ecclesiaflow.platform.rpc.logging;

import com.ecclesiaflow.platform.logging.SecurityMaskingUtils;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;

/** Logs the token operations; {@code ecclesiaflow.platform.rpc.logging.enabled=false} silences it. */
@Slf4j
@Aspect
public class PlatformRpcLoggingAspect {

    @Pointcut("execution(* com.ecclesiaflow.platform.rpc.s2s.token.S2sTokenClient.fetchToken(..))")
    public void tokenClientFetch() {}

    @Pointcut("execution(* com.ecclesiaflow.platform.rpc.s2s.token.S2sTokenProvider.getToken(..))")
    public void tokenProviderGetToken() {}

    @AfterReturning("tokenClientFetch()")
    public void logFetchSuccess(JoinPoint joinPoint) {
        log.info("S2S: ✅ Obtained fresh s2s token from Keycloak");
    }

    @AfterThrowing(pointcut = "tokenClientFetch()", throwing = "exception")
    public void logFetchFailure(JoinPoint joinPoint, Throwable exception) {
        log.error("S2S: ❌ Failed to obtain s2s token — {}: {}",
                exception.getClass().getSimpleName(),
                SecurityMaskingUtils.sanitizeInfra(exception.getMessage()));
    }

    // A getToken() without a fetch is a cache hit, so hits are not logged.
    @AfterThrowing(pointcut = "tokenProviderGetToken()", throwing = "exception")
    public void logProviderFailure(JoinPoint joinPoint, Throwable exception) {
        // Distinct from the fetch failure: a caller got no token, not only a failed network call.
        log.warn("S2S: ❌ getToken() failed for caller — {}",
                SecurityMaskingUtils.sanitizeInfra(exception.getMessage()));
    }
}
