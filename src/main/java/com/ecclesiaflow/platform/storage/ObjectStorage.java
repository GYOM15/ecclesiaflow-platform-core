package com.ecclesiaflow.platform.storage;

import java.util.Optional;

/**
 * Provider-agnostic binary object store — the platform's seam for keeping large
 * blobs (member photos, church logos, event / announcement / group images) OUT
 * of the relational database.
 *
 * <p>Hexagonal port: business/persistence code depends on this interface only,
 * never on a concrete backend. Adapters live under this package
 * ({@code FilesystemObjectStorage} for local dev/tests,
 * {@code s3.S3ObjectStorage} for Cloudflare R2 / any S3-compatible store) and
 * are selected by a single configuration knob
 * ({@code ecclesiaflow.object-storage.provider}). Swapping providers is a config
 * change, never a code change — mirrors the {@code EmailProvider} pattern.</p>
 *
 * <p>Keys are opaque, provider-generated, and content-addressed by randomness
 * ({@code <prefix>/<uuid>.<ext>}) so a stored object is immutable: re-uploading
 * yields a NEW key and the caller deletes the old one. That lets every object be
 * served with a long, immutable cache lifetime. The caller persists only the
 * returned {@link StoredObjectRef#key()} — treat it as an opaque token.</p>
 *
 * <p>Adapters must never throw checked exceptions or leak provider types across
 * this boundary: I/O and backend failures surface as {@link ObjectStorageException}.</p>
 */
public interface ObjectStorage {

    /**
     * Stores {@code data} under a freshly generated key beneath {@code keyPrefix}
     * (e.g. {@code "member-photos"}), deriving the extension from
     * {@code contentType}. The key is never derived from any client-supplied
     * filename.
     *
     * @param keyPrefix  logical bucket/folder for this object class (no leading
     *                   or trailing slash); must be non-blank
     * @param data       the object bytes; must be non-null and non-empty
     * @param contentType the validated MIME type (e.g. {@code image/png}); the
     *                    caller is responsible for validating/sniffing it first
     * @return a reference holding the opaque storage key to persist
     * @throws ObjectStorageException if the object cannot be written
     */
    StoredObjectRef put(String keyPrefix, byte[] data, String contentType);

    /**
     * Fetches a stored object by its key.
     *
     * @param key the opaque key previously returned by {@link #put}
     * @return the object, or {@link Optional#empty()} if no object exists at that key
     * @throws ObjectStorageException on a backend failure other than "not found"
     */
    Optional<StoredObject> get(String key);

    /**
     * Deletes the object at {@code key}. Idempotent: deleting an absent key is a
     * no-op, not an error.
     *
     * @param key the opaque key to delete
     * @throws ObjectStorageException on a backend failure
     */
    void delete(String key);

    /**
     * @return a short, human-readable provider identifier for diagnostics/logs
     *         (e.g. {@code "filesystem"}, {@code "s3"}). Never leaks credentials.
     */
    String providerName();
}
