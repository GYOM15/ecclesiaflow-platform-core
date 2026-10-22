package com.ecclesiaflow.platform.idempotency;

import java.util.Optional;
import java.util.UUID;

/**
 * What {@link IdempotentCreator} needs from the module's own table. That table stores the client's key and the
 * request's fingerprint on the created row ({@link IdempotencyStamp}), under a unique index on the author
 * column and the key column: the index, not this code, settles two concurrent requests.
 *
 * @param <A> the author, as the unique index stores it
 * @param <C> the creation request, path parameters included
 * @param <R> the created resource
 */
public interface IdempotentCreation<A, C, R> {

    /**
     * The row this author created under this key, as stored, even if it was edited or hidden since: a retry
     * must replay the creation, not create again. Never a row of another author, since a key is only unique
     * per author and another author's row would leak to the caller.
     */
    Optional<IdempotentRecord<R>> find(A author, UUID key);

    /**
     * Equal for two requests that create the same thing and different otherwise, across instances and
     * deploys: a retry with the same key replays, another request under it is refused. Build it with
     * {@link IdempotencyFingerprint#of(Object...)} over every field of the request.
     */
    String fingerprint(C command);

    /**
     * Creates the resource and writes {@code stamp.key()} and {@code stamp.fingerprint()} on its row as they
     * are, {@code null} included. Runs in the transaction the creator opened, so a duplicate key aborts it
     * whole.
     */
    R insert(A author, C command, IdempotencyStamp stamp);
}
