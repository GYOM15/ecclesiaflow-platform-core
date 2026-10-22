package com.ecclesiaflow.platform.idempotency.events;

import java.util.UUID;

/** Decisions of the idempotent creator, which never logs: listeners turn them into logs. */
public final class IdempotencyEvents {

    private IdempotencyEvents() {
    }

    /**
     * A request answered with the row an earlier one created under the same key.
     *
     * @param operation the contract label of the operation
     * @param author    the author column's value, raw: the listener masks it
     * @param key       the client's key, raw: the listener masks it
     * @param raced     {@code true} when the unique index refused this request's own insert, so both requests
     *                  were in flight together
     */
    public record Replayed(String operation, Object author, UUID key, boolean raced) {
    }

    /** A key reused by its author for a different request, refused. Fields as in {@link Replayed}. */
    public record Conflicted(String operation, Object author, UUID key, boolean raced) {
    }
}
