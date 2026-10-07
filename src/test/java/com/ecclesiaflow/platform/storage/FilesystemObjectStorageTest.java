package com.ecclesiaflow.platform.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
    @DisplayName("a local store has no public URL and owns no URL")
    void noPublicUrls() {
        StoredObjectRef ref = storage.put("church-logos", "x".getBytes(StandardCharsets.UTF_8), "image/png");

        assertThat(storage.publicUrl(ref.key())).isEmpty();
        assertThat(storage.isOwnPublicUrl("https://cdn.example.com/" + ref.key())).isFalse();
    }

    @Test
    @DisplayName("providerName is filesystem")
    void providerName() {
        assertThat(storage.providerName()).isEqualTo("filesystem");
    }

    @Test
    @DisplayName("null data is rejected like empty data")
    void nullDataRejected() {
        assertThatThrownBy(() -> storage.put("logos", null, "image/png"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a blank or missing key is a caller error, on read and on delete")
    void blankKeyRejected() {
        assertThatThrownBy(() -> storage.get(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.get(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.delete("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a base path that is a file fails at startup, not at the first upload")
    void unusableBaseDirectoryFailsAtConstruction() throws IOException {
        Path occupied = Files.writeString(tmp.resolve("occupied"), "not a directory");

        assertThatThrownBy(() -> new FilesystemObjectStorage(occupied.toString()))
                .isInstanceOf(ObjectStorageException.class)
                .hasMessageContaining("base directory")
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("a write the disk refuses surfaces as ObjectStorageException")
    void failedWriteIsWrapped() throws IOException {
        Files.writeString(tmp.resolve("logos"), "a file where the prefix directory should be");

        assertThatThrownBy(() -> storage.put("logos", new byte[]{1}, "image/png"))
                .isInstanceOf(ObjectStorageException.class)
                .hasMessageContaining("write")
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("deleting a whole prefix is refused, not performed")
    void deletingAPrefixIsRefused() {
        storage.put("logos", new byte[]{1}, "image/png");

        assertThatThrownBy(() -> storage.delete("logos"))
                .isInstanceOf(ObjectStorageException.class)
                .hasMessageContaining("delete")
                .hasCauseInstanceOf(IOException.class);
        assertThat(tmp.resolve("logos")).isDirectory();
    }
}
