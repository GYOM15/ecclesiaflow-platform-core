package com.ecclesiaflow.platform.rpc.s2s.token;
import com.ecclesiaflow.platform.rpc.s2s.S2sProperties;


import java.util.concurrent.locks.ReentrantLock;

/**
 * Public entry point for callers that need a valid s2s access token.
 *
 * <p><strong>Single responsibility:</strong> orchestrate {@link S2sTokenCache}
 * and {@link S2sTokenClient}. Reads the cache; on miss, holds a lock so that
 * concurrent callers result in a single network call, then publishes the fresh
 * token to the cache.</p>
 *
 * <p>No HTTP, no parsing, no logging — those are delegated, in line with the
 * platform's "logs in aspects only" rule.</p>
 */
public class S2sTokenProvider {

    private final S2sTokenClient client;
    private final S2sTokenCache cache;
    private final int refreshLeewaySeconds;
    private final ReentrantLock refreshLock = new ReentrantLock();

    public S2sTokenProvider(S2sTokenClient client, S2sTokenCache cache, S2sProperties props) {
        this.client = client;
        this.cache = cache;
        this.refreshLeewaySeconds = props.getRefreshLeewaySeconds();
    }

    /**
     * Returns a currently valid access token, refreshing it if necessary.
     *
     * @throws S2sTokenException if Keycloak is unreachable or rejects the credentials
     */
    public String getToken() {
        return cache.getIfValid(refreshLeewaySeconds)
                .orElseGet(this::refresh)
                .accessToken();
    }

    /**
     * Drops the cached token. Next {@link #getToken()} call will fetch a fresh one.
     * Useful when the server signals an authentication problem mid-call and we want
     * to retry with a freshly minted JWT.
     */
    public void invalidate() {
        cache.invalidate();
    }

    private S2sToken refresh() {
        refreshLock.lock();
        try {
            // Another thread may have refreshed while we waited on the lock.
            return cache.getIfValid(refreshLeewaySeconds).orElseGet(() -> {
                S2sToken fresh = client.fetchToken();
                cache.put(fresh);
                return fresh;
            });
        } finally {
            refreshLock.unlock();
        }
    }
}
