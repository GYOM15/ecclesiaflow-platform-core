package com.ecclesiaflow.platform.storage;

import java.util.Optional;

/**
 * Binary object store that keeps large blobs out of the database; the adapter is chosen by
 * {@code ecclesiaflow.object-storage.provider}.
 *
 * <p>Keys are generated ({@code <prefix>/<uuid>.<ext>}), never derived from a client filename, so an
 * object is immutable: a re-upload gets a new key and the caller deletes the old one, which lets public
 * objects be cached for good. Persist the key as an opaque token. Backend failures surface as
 * {@link ObjectStorageException}, never as provider types.</p>
 */
public interface ObjectStorage {

    /** {@code contentType} must already be sniffed; {@code keyPrefix} has no leading or trailing slash. */
    StoredObjectRef put(String keyPrefix, byte[] data, String contentType);

    /** Empty when no object exists at {@code key}. */
    Optional<StoredObject> get(String key);

    /** Idempotent: deleting an absent key is a no-op. */
    void delete(String key);

    /** Empty when the store has no public base, or the object must only be served through the backend. */
    default Optional<String> publicUrl(String key) {
        return Optional.empty();
    }

    default boolean isOwnPublicUrl(String url) {
        return false;
    }

    /** For diagnostics, e.g. {@code "s3"}; never carries credentials. */
    String providerName();
}
