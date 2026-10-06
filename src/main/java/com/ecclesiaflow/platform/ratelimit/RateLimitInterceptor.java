package com.ecclesiaflow.platform.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Optional;

/** Runs before the controller, so a refused call costs one Redis round trip and nothing else. */
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

        // An undeclared rule fails loudly: a limit everyone believes in that silently
        // does not apply is worse than no limit.
        RateLimitRule rule = registry.find(annotation.value()).orElseThrow(
                () -> new IllegalStateException(
                        "No rate limit rule named '" + annotation.value() + "' is registered, "
                                + "but " + method.getShortLogMessage() + " asks for it"));

        Optional<String> subject = subjects.resolve(rule.scope());
        if (subject.isEmpty()) {
            // Let through: the operation's own authorization refuses it next, and a shared
            // subject would let one caller spend everyone's allowance.
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
