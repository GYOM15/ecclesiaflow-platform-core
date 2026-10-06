package com.ecclesiaflow.platform.ratelimit;

import java.util.Map;
import java.util.Optional;

/**
 * The module's limits in one place, so the policy reads as a policy instead of being reconstructed from
 * annotations. An unknown rule name is a programming error: {@link RateLimitedHandlerValidator} refuses
 * to start rather than let the call through unlimited.
 */
public interface RateLimitRuleRegistry {

    Map<String, RateLimitRule> rules();

    default Optional<RateLimitRule> find(String name) {
        return Optional.ofNullable(rules().get(name));
    }
}
