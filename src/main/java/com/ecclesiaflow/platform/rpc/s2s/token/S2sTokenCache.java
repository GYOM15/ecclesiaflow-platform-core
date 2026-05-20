package com.ecclesiaflow.platform.rpc.s2s.token;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory, thread-safe cache for the platform's s2s access token.
 *
 * <p><strong>Single responsibility:</strong> hold the current token and answer the
 * question "is it still valid?" given a leeway. No HTTP, no logging, no refresh
 * logic — those live in {@link S2sTokenClient} and {@link S2sTokenProvider}.</p>
 *
 * <p>Concurrency model: the cached reference is held in an {@link AtomicReference},
 * so reads are lock-free. Writes are still cheap (single CAS) but may be safely
 * concurrent — the provider is responsible for ensuring only one refresh is in
 * flight at a time.</p>
 */
public class S2sTokenCache {

    private final AtomicReference<S2sToken> ref = new AtomicReference<>();

    /**
     * Returns the cached token if it is still valid more than {@code leewaySeconds} from now.
     *
     * @param leewaySeconds safety window before {@code exp} to consider the token already expired
     * @return present {@link Optional} when the cache holds a token whose expiry is far enough
     *         in the future; empty otherwise
     */
    public Optional<S2sToken> getIfValid(int leewaySeconds) {
        S2sToken snapshot = ref.get();
        if (snapshot == null) {
            return Optional.empty();
        }
        if (Instant.now().plusSeconds(leewaySeconds).isBefore(snapshot.expiry())) {
            return Optional.of(snapshot);
        }
        return Optional.empty();
    }

    /** Replaces the cached token. Subsequent {@link #getIfValid(int)} calls see the new value. */
    public void put(S2sToken token) {
        ref.set(token);
    }

    /** Drops the cached token. Visible for testing and admin endpoints. */
    public void invalidate() {
        ref.set(null);
    }
}
