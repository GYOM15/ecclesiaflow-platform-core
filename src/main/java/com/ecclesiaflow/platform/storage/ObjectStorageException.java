package com.ecclesiaflow.platform.storage;

/** Any failure other than "not found"; messages never carry credentials or object bytes. */
public class ObjectStorageException extends RuntimeException {

    public ObjectStorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public ObjectStorageException(String message) {
        super(message);
    }
}
