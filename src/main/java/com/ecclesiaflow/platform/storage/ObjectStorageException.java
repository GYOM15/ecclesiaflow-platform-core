package com.ecclesiaflow.platform.storage;

/**
 * Unchecked failure raised by an {@link ObjectStorage} adapter when an object
 * cannot be written, read, or deleted for any reason other than a benign
 * "not found" (which {@link ObjectStorage#get} models as an empty result and
 * {@link ObjectStorage#delete} treats as a no-op).
 *
 * <p>Keeps provider-specific exception types (S3 SDK, java.io) from leaking
 * across the port. Messages must never contain credentials or object bytes.</p>
 */
public class ObjectStorageException extends RuntimeException {

    public ObjectStorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public ObjectStorageException(String message) {
        super(message);
    }
}
