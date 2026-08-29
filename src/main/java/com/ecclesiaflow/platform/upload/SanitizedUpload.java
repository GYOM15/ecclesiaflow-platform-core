package com.ecclesiaflow.platform.upload;

import java.util.Objects;

/**
 * The safe, server-produced result of running a user upload through a sanitizer.
 *
 * <p>These bytes are NOT the bytes the client sent. For images they are the
 * output of a full decode → resample → native re-encode cycle (see
 * {@link ImageSanitizer}); for non-image files they are the original bytes but
 * only ever after the real media type was proven by content sniffing (see
 * {@link FileSanitizer}). In both cases {@link #contentType()} is the
 * <em>detected</em> type, never a client-declared one — callers persist and
 * serve THIS type, and hand THESE bytes to
 * {@link com.ecclesiaflow.platform.storage.ObjectStorage#put}.</p>
 *
 * @param data        the sanitized object bytes (never null)
 * @param contentType the authoritative, sniffed MIME type (never blank)
 * @param size        the length of {@link #data} in bytes
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
