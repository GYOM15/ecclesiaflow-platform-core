package com.ecclesiaflow.platform.ratelimit;

import java.util.Optional;

/**
 * Supplied by each module. The subject must come from the server's view of the caller (validated token,
 * resolved membership), never a header or body field: a subject the client chooses is a limit it resets.
 */
public interface RateLimitSubjectResolver {

    /** Empty when the scope cannot be resolved for this call; the interceptor then lets the call through. */
    Optional<String> resolve(RateLimitScope scope);
}
