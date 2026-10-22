package com.ecclesiaflow.platform.idempotency;

import java.util.Objects;
import java.util.UUID;

/**
 * The client's {@code Idempotency-Key} on an operation that runs the primitive. Only the web layer builds one,
 * from the contract, so a header sent to any other operation never reaches a creation.
 *
 * @param value     the key the client drew, unique per author only
 * @param operation the contract label of the operation it was sent to
 */
public record IdempotencyKey(UUID value, String operation) {

    public IdempotencyKey {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(operation, "operation");
    }
}
