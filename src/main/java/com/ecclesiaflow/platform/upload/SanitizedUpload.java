package com.ecclesiaflow.platform.upload;

import java.util.Objects;

/**
 * Server-produced bytes, not the client's: persist and serve {@link #contentType()}, the sniffed type,
 * and hand {@link #data()} to {@link com.ecclesiaflow.platform.storage.ObjectStorage#put}.
 */
public record SanitizedUpload(byte[] data, String contentType, int size) {

    public SanitizedUpload {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(contentType, "contentType");
        if (contentType.isBlank()) {
            throw new IllegalArgumentException("contentType must not be blank");
        }
    }
}
