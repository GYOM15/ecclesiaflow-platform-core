package com.ecclesiaflow.platform.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FilesystemObjectStorage - local-disk adapter")
class FilesystemObjectStorageTest {

    @TempDir
    Path tmp;

    private ObjectStorage storage;

    @BeforeEach
    void setUp() {
        storage = new FilesystemObjectStorage(tmp.toString());
    }

    @Test
    @DisplayName("put then get round-trips the bytes and resolves the content type from the key")
    void roundTrip() {
        byte[] data = "the-bytes".getBytes(StandardCharsets.UTF_8);

        StoredObjectRef ref = storage.put("member-photos", data, "image/png");

        assertThat(ref.key()).matches("member-photos/[0-9a-fA-F-]{36}\\.png");
        StoredObject fetched = storage.get(ref.key()).orElseThrow();
        assertThat(fetched.data()).isEqualTo(data);
        assertThat(fetched.contentType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("get on an absent key is empty, not an error")
    void getMissing() {
        assertThat(storage.get("member-photos/00000000-0000-0000-0000-000000000000.png")).isEmpty();
    }

    @Test
    @DisplayName("delete removes the object and is idempotent")
    void deleteIdempotent() {
        StoredObjectRef ref = storage.put("logos", "x".getBytes(StandardCharsets.UTF_8), "image/jpeg");

        storage.delete(ref.key());
        assertThat(storage.get(ref.key())).isEmpty();
        // Second delete must not throw.
        storage.delete(ref.key());
    }

    @Test
    @DisplayName("empty data is rejected")
    void emptyDataRejected() {
        assertThatThrownBy(() -> storage.put("logos", new byte[0], "image/png"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a key escaping the base directory is rejected")
    void traversalRejected() {
        assertThatThrownBy(() -> storage.get("../../../etc/passwd"))
                .isInstanceOf(ObjectStorageException.class);
    }

    @Test
    @DisplayName("providerName is filesystem")
    void providerName() {
        assertThat(storage.providerName()).isEqualTo("filesystem");
    }
}
