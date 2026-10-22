package com.ecclesiaflow.platform.idempotency;

/**
 * The author already used this key for another request: a client bug, never retried. platform-core's REST
 * fallback answers it 422 {@code IDEMPOTENCY_CONFLICT}. Distinct from church's legacy
 * {@code IdempotencyConflictException}, whose operations keep their own answers.
 */
public class IdempotencyKeyConflictException extends RuntimeException {

    private final String operation;

    public IdempotencyKeyConflictException(String operation) {
        super("The Idempotency-Key was already used for a different request on " + operation);
        this.operation = operation;
    }

    /** The contract label of the operation the key was reused on. */
    public String getOperation() {
        return operation;
    }
}
