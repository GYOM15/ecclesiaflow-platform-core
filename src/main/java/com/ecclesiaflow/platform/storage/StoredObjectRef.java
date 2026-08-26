package com.ecclesiaflow.platform.storage;

import java.util.Objects;

/**
 * The opaque handle {@link ObjectStorage#put} returns and the caller persists —
 * everything needed to fetch or delete the object later.
 *
 * <p>Only {@link #key()} is stored in the database; it is provider-generated and
 * must be treated as opaque (never parsed for meaning by business code).</p>
 *
 * @param key the storage key (e.g. {@code member-photos/3f2a....png})
 */
public record StoredObjectRef(String key) {

    public StoredObjectRef {
        Objects.requireNonNull(key, "key");
        if (key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
    }
}
