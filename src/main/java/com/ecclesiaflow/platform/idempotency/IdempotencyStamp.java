package com.ecclesiaflow.platform.idempotency;

import java.util.UUID;

/**
 * What a created row stores so a retry can find it and tell it from another request: the client's key and the
 * request's fingerprint, written as they are into their two columns.
 *
 * @param key         {@code null} when the request carries no key
 * @param fingerprint {@code null} exactly when {@code key} is
 */
public record IdempotencyStamp(UUID key, String fingerprint) {

    /** A creation without a key: both columns stay {@code null}, which the unique index never compares. */
    public static final IdempotencyStamp NONE = new IdempotencyStamp(null, null);

    public IdempotencyStamp {
        if ((key == null) != (fingerprint == null)) {
            throw new IllegalArgumentException("An idempotency key and its fingerprint are stored together or not at all");
        }
        if (fingerprint != null && fingerprint.isBlank()) {
            throw new IllegalArgumentException("A blank fingerprint would let any body replay the first one");
        }
    }
}
