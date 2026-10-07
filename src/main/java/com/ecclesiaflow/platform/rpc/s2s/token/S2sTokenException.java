package com.ecclesiaflow.platform.rpc.s2s.token;

/** Unchecked so the {@code ClientInterceptor} signature stays clean; callers map it to a gRPC status. */
public class S2sTokenException extends RuntimeException {

    public S2sTokenException(String message) {
        super(message);
    }

    public S2sTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
