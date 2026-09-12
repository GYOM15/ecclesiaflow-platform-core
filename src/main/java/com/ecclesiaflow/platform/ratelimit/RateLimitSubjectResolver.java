package com.ecclesiaflow.platform.ratelimit;

import java.util.Optional;

/**
 * Answers WHO the current call belongs to, for a given scope.
 *
 * <p>Each module supplies its own: the platform library has no opinion on how a
 * church id or a user id is carried, and every module already has a resolver for
 * its own authentication.
 *
 * <p>The answer must come from the SERVER's view of the caller — a validated
 * token, a resolved membership — and never from a header or a body field. A
 * subject the client can choose is a limit the client can reset by choosing a
 * different one.
 */
public interface RateLimitSubjectResolver {

    /**
     * @return the subject to count against, or empty when the scope cannot be
     *         resolved for this call (an unauthenticated request under a
     *         per-church rule, say). The interceptor then lets the call through:
     *         the operation's own authorization will refuse it a moment later,
     *         and inventing a shared subject like "anonymous" would let one
     *         caller exhaust everyone else's allowance.
     */
    Optional<String> resolve(RateLimitScope scope);
}
