package com.ecclesiaflow.platform.error;

/**
 * Names mirror the gRPC canonical codes so the RPC mapping is one-to-one; each HTTP status is the
 * one the services' REST contracts already use for that situation.
 */
public enum ErrorCategory {

    INVALID_ARGUMENT(400),
    UNAUTHENTICATED(401),
    PERMISSION_DENIED(403),
    NOT_FOUND(404),
    ALREADY_EXISTS(409),
    FAILED_PRECONDITION(409),
    ABORTED(409),
    RESOURCE_EXHAUSTED(429),
    UNAVAILABLE(503),
    INTERNAL(500);

    private final int httpStatus;

    ErrorCategory(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
