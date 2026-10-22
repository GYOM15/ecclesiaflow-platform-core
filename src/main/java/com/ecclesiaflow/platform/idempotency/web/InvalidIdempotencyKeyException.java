package com.ecclesiaflow.platform.idempotency.web;

/**
 * An {@code Idempotency-Key} that is not one canonical UUID, on an operation that runs the primitive. Answered
 * 400 by platform-core's REST fallback, as Spring answers a malformed UUID header it binds. Not an
 * {@link IllegalArgumentException}, which modules answer with their own code.
 */
public class InvalidIdempotencyKeyException extends RuntimeException {

    public InvalidIdempotencyKeyException() {
        super("The " + IdempotencyHeaders.KEY + " header must hold exactly one UUID");
    }
}
