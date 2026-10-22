package com.ecclesiaflow.platform.idempotency;

import java.util.Objects;

/**
 * The resource a creation answers with, and whether it was created by this request or replayed from an
 * earlier one with the same key, which the response tells the client.
 */
public record CreationOutcome<R>(R resource, boolean replayed) {

    public CreationOutcome {
        Objects.requireNonNull(resource, "resource");
    }

    public static <R> CreationOutcome<R> created(R resource) {
        return new CreationOutcome<>(resource, false);
    }

    public static <R> CreationOutcome<R> replay(R resource) {
        return new CreationOutcome<>(resource, true);
    }
}
