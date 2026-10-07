package com.ecclesiaflow.platform.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

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
    @DisplayName("a local store finds no key behind any URL")
    void noKeyBehindAnyUrl() {
        StoredObjectRef ref = storage.put("images", "x".getBytes(StandardCharsets.UTF_8), "image/png");

        assertThat(storage.keyOfOwnPublicUrl("https://cdn.example.com/" + ref.key())).isEmpty();
        assertThat(storage.keyOfOwnPublicUrl(ref.key())).isEmpty();
        assertThat(storage.keyOfOwnPublicUrl(null)).isEmpty();
    }

    @Nested
    @DisplayName("copy")
    class Copy {

        @Test
        @DisplayName("the target holds the same bytes and content type, and the source stays")
        void copiesBytesAndType() {
            byte[] data = "the-bytes".getBytes(StandardCharsets.UTF_8);
            StoredObjectRef source = storage.put("uploads/church-1", data, "image/webp");
            String target = "images/church-1/" + UUID.randomUUID() + ".webp";

            storage.copy(source.key(), target);

            StoredObject copied = storage.get(target).orElseThrow();
            assertThat(copied.data()).isEqualTo(data);
            assertThat(copied.contentType()).isEqualTo("image/webp");
            assertThat(storage.get(source.key())).isPresent();
        }

        @Test
        @DisplayName("an existing target is replaced, as an S3 copy does")
        void replacesExistingTarget() {
            StoredObjectRef source = storage.put("uploads", new byte[]{1, 2}, "image/png");
            StoredObjectRef target = storage.put("images", new byte[]{9}, "image/png");

            storage.copy(source.key(), target.key());

            assertThat(storage.get(target.key()).orElseThrow().data()).containsExactly(1, 2);
        }

        @Test
        @DisplayName("an absent source is reported as not found and writes nothing")
        void absentSource() {
            String target = "images/" + UUID.randomUUID() + ".png";

            assertThatThrownBy(() -> storage.copy("uploads/" + UUID.randomUUID() + ".png", target))
                    .isInstanceOf(StoredObjectNotFoundException.class);
            assertThat(storage.get(target)).isEmpty();
        }

        @Test
        @DisplayName("a key escaping the base directory is rejected on either side")
        void traversalRejected() {
            StoredObjectRef source = storage.put("uploads", new byte[]{1}, "image/png");

            assertThatThrownBy(() -> storage.copy(source.key(), "../outside.png"))
                    .isInstanceOf(ObjectStorageException.class);
            assertThatThrownBy(() -> storage.copy("../../etc/passwd", "images/a.png"))
                    .isInstanceOf(ObjectStorageException.class);
            assertThat(tmp.getParent().resolve("outside.png")).doesNotExist();
        }

        @Test
        @DisplayName("a blank or missing key is a caller error")
        void blankKeyRejected() {
            assertThatThrownBy(() -> storage.copy(" ", "images/a.png")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.copy("uploads/a.png", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a write the disk refuses surfaces as ObjectStorageException")
        void failedWriteIsWrapped() throws IOException {
            StoredObjectRef source = storage.put("uploads", new byte[]{1}, "image/png");
            Files.writeString(tmp.resolve("images"), "a file where the prefix directory should be");

            assertThatThrownBy(() -> storage.copy(source.key(), "images/a.png"))
                    .isInstanceOf(ObjectStorageException.class)
                    .hasMessageContaining("copy")
                    .hasCauseInstanceOf(IOException.class);
        }
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
