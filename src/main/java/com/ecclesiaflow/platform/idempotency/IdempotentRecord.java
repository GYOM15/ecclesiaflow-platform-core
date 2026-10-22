package com.ecclesiaflow.platform.idempotency;

import java.util.Objects;

/**
 * A row found by its author and key: the resource a replay answers with, and the fingerprint stored with the
 * key, compared with the retried request's.
 */
public record IdempotentRecord<R>(R resource, String fingerprint) {

    public IdempotentRecord {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(fingerprint, "fingerprint");
    }
}
