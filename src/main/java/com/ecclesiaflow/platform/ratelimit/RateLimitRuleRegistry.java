package com.ecclesiaflow.platform.ratelimit;

import java.util.Map;
import java.util.Optional;

/**
 * The module's limits, in one place.
 *
 * <p>Each module supplies one of these as a bean. Keeping every number together
 * means the policy can be read as a policy — «  how hard is it to hammer this
 * product » — instead of being reconstructed from annotations scattered over
 * twenty controllers.
 *
 * <p>An unknown rule name is a PROGRAMMING error, not a request error: it means
 * a method claims a limit nobody declared. The interceptor refuses to start
 * rather than letting the call through unlimited, because a limit that silently
 * does not apply is worse than no limit — it is a limit everybody believes in.
 */
public interface RateLimitRuleRegistry {

    /** Every rule this module declares, by name. */
    Map<String, RateLimitRule> rules();

    default Optional<RateLimitRule> find(String name) {
        return Optional.ofNullable(rules().get(name));
    }
}
