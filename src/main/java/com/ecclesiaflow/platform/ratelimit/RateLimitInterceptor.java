package com.ecclesiaflow.platform.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Optional;

/**
 * Applies the {@link RateLimited} annotation found on the handler.
 *
 * <p>Runs BEFORE the controller, so a refused call costs one Redis round trip
 * and nothing else — no transaction, no query, no fan-out. That is the whole
 * point of limiting an expensive operation: the expense must not be paid before
 * the refusal.
 */
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter limiter;
    private final RateLimitRuleRegistry registry;
    private final RateLimitSubjectResolver subjects;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RateLimited annotation = method.getMethodAnnotation(RateLimited.class);
        if (annotation == null) {
            return true;
        }

        // An unknown name means a method claims a limit nobody declared. Failing
        // loudly beats letting the call through: a limit everyone believes in
        // and that silently does not apply is worse than no limit at all.
        RateLimitRule rule = registry.find(annotation.value()).orElseThrow(
                () -> new IllegalStateException(
                        "No rate limit rule named '" + annotation.value() + "' is registered, "
                                + "but " + method.getShortLogMessage() + " asks for it"));

        Optional<String> subject = subjects.resolve(rule.scope());
        if (subject.isEmpty()) {
            // Nothing to count against. Letting it through is deliberate: the
            // operation's own authorization refuses it a moment later, and
            // inventing a shared subject would let one caller spend everyone's
            // allowance.
            return true;
        }

        RateLimitDecision decision = limiter.consume(rule, subject.get());
        response.setHeader("RateLimit-Limit", String.valueOf(decision.limit()));
        response.setHeader("RateLimit-Remaining", String.valueOf(decision.remaining()));

        if (!decision.allowed()) {
            response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
            throw new RateLimitExceededException(rule.name(), decision.retryAfterSeconds());
        }
        return true;
    }
}
