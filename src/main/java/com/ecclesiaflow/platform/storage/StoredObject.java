package com.ecclesiaflow.platform.storage;

import java.util.Objects;

/** {@code contentType} comes from the key's extension, so a caller needs no metadata column to serve it. */
public record StoredObject(byte[] data, String contentType) {

    public StoredObject {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(contentType, "contentType");
        if (contentType.isBlank()) {
            throw new IllegalArgumentException("contentType must not be blank");
        }
    }
}
