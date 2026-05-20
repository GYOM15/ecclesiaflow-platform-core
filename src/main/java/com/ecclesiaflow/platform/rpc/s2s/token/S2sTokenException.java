package com.ecclesiaflow.platform.rpc.s2s.token;

/**
 * Thrown when the platform fails to obtain a server-to-server access token
 * from Keycloak (network error, invalid credentials, malformed response, etc.).
 *
 * <p>Surfacing this as a dedicated unchecked exception keeps the {@code ClientInterceptor}
 * signature clean and lets consumers translate it to a gRPC status of their choosing
 * (usually {@code UNAVAILABLE}).</p>
 */
public class S2sTokenException extends RuntimeException {

    public S2sTokenException(String message) {
        super(message);
    }

    public S2sTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
