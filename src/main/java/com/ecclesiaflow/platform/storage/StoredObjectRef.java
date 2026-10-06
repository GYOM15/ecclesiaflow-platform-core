package com.ecclesiaflow.platform.storage;

import java.util.Objects;

public record StoredObjectRef(String key) {

    public StoredObjectRef {
        Objects.requireNonNull(key, "key");
        if (key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
    }
}
