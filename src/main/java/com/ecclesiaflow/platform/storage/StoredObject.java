package com.ecclesiaflow.platform.storage;

import java.util.Objects;

/**
 * The bytes and MIME type returned by {@link ObjectStorage#get}. The
 * {@code contentType} is resolved from the stored key's extension, so a caller
 * can serve the object without consulting its own metadata columns.
 *
 * @param data        the object bytes (never null)
 * @param contentType the resolved MIME type (never blank)
 */
public record StoredObject(byte[] data, String contentType) {

    public StoredObject {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(contentType, "contentType");
        if (contentType.isBlank()) {
            throw new IllegalArgumentException("contentType must not be blank");
        }
    }
}
