package com.ecclesiaflow.platform.ratelimit;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Refuses to start an application in which a {@link RateLimited} handler would not be
 * limited: its rule is not in the registry, or the limiter cannot be wired at all.
 *
 * <p>The interceptor only sees an undeclared rule on the first request to that handler,
 * and an unwired limiter is never consulted, so both faults would otherwise pass for a
 * working limit.
 */
@RequiredArgsConstructor
public class RateLimitedHandlerValidator implements SmartInitializingSingleton {

    private final ObjectProvider<RequestMappingHandlerMapping> handlerMappings;
    private final ObjectProvider<RateLimiter> limiter;
    private final ObjectProvider<RateLimitRuleRegistry> registry;
    private final ObjectProvider<RateLimitSubjectResolver> subjects;

    @Override
    public void afterSingletonsInstantiated() {
        validate(handlerMappings.orderedStream()
                .flatMap(mapping -> mapping.getHandlerMethods().values().stream())
                .toList());
    }

    /**
     * @throws IllegalStateException naming every limited handler that would not be limited
     */
    void validate(Collection<HandlerMethod> handlers) {
        List<HandlerMethod> limited = handlers.stream()
                .filter(handler -> handler.getMethodAnnotation(RateLimited.class) != null)
                .distinct()
                .toList();
        if (limited.isEmpty()) {
            return;
        }

        List<String> missing = missingCollaborators();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Rate limiting is not wired: no " + String.join(", no ", missing)
                    + " bean. These handlers would run unlimited: " + describe(limited));
        }

        RateLimitRuleRegistry rules = registry.getObject();
        List<String> undeclared = limited.stream()
                .filter(handler -> rules.find(ruleName(handler)).isEmpty())
                .map(handler -> "'" + ruleName(handler) + "' on " + handler.getShortLogMessage())
                .toList();
        if (!undeclared.isEmpty()) {
            throw new IllegalStateException(
                    "No rate limit rule is registered for " + String.join(", ", undeclared));
        }
    }

    private List<String> missingCollaborators() {
        List<String> missing = new ArrayList<>();
        if (limiter.getIfAvailable() == null) {
            missing.add(RateLimiter.class.getSimpleName());
        }
        if (registry.getIfAvailable() == null) {
            missing.add(RateLimitRuleRegistry.class.getSimpleName());
        }
        if (subjects.getIfAvailable() == null) {
            missing.add(RateLimitSubjectResolver.class.getSimpleName());
        }
        return missing;
    }

    private static String ruleName(HandlerMethod handler) {
        return handler.getMethodAnnotation(RateLimited.class).value();
    }

    private static String describe(List<HandlerMethod> handlers) {
        return handlers.stream().map(HandlerMethod::getShortLogMessage).collect(Collectors.joining(", "));
    }
}
