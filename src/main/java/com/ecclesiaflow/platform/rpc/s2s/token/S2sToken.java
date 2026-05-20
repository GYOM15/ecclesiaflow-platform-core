package com.ecclesiaflow.platform.rpc.s2s.token;

import java.time.Instant;

/**
 * Immutable carrier for a server-to-server access token and its absolute expiry.
 *
 * <p>Kept as a top-level type so that {@link S2sTokenClient}, {@link S2sTokenCache}
 * and {@link S2sTokenProvider} share a stable contract without coupling them
 * to a private inner class.</p>
 *
 * @param accessToken the raw JWT compact form, never {@code null}
 * @param expiry      the absolute instant after which the token MUST NOT be used
 */
public record S2sToken(String accessToken, Instant expiry) {
}
