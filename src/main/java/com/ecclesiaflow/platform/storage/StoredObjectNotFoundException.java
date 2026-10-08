package com.ecclesiaflow.platform.storage;

/** Kept apart from {@link ObjectStorageException} so a caller can tell a missing object from an outage. */
public class StoredObjectNotFoundException extends RuntimeException {

    public StoredObjectNotFoundException(String message) {
        super(message);
    }

    public StoredObjectNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
