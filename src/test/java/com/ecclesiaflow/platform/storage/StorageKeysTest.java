package com.ecclesiaflow.platform.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("StorageKeys - key generation & MIME mapping")
class StorageKeysTest {

    @Test
    @DisplayName("newKey builds <prefix>/<uuid>.<ext>")
    void newKeyShape() {
        assertThat(StorageKeys.newKey("member-photos", "image/png"))
                .matches("member-photos/[0-9a-fA-F-]{36}\\.png");
    }

    @Test
    @DisplayName("newKey strips surrounding slashes from the prefix")
    void newKeyNormalisesPrefix() {
        assertThat(StorageKeys.newKey("/church-logos/", "image/jpeg"))
                .startsWith("church-logos/")
                .endsWith(".jpg");
    }

    @Test
    @DisplayName("newKey is unique per call")
    void newKeyUnique() {
        assertThat(StorageKeys.newKey("p", "image/png"))
                .isNotEqualTo(StorageKeys.newKey("p", "image/png"));
    }

    @Test
    @DisplayName("blank prefix is rejected")
    void blankPrefixRejected() {
        assertThatThrownBy(() -> StorageKeys.newKey("   ", "image/png"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("extensionFor maps the accepted image types")
    void extensionForKnown() {
        assertThat(StorageKeys.extensionFor("image/jpeg")).isEqualTo("jpg");
        assertThat(StorageKeys.extensionFor("image/png")).isEqualTo("png");
        assertThat(StorageKeys.extensionFor("image/webp")).isEqualTo("webp");
        assertThat(StorageKeys.extensionFor("image/gif")).isEqualTo("gif");
        assertThat(StorageKeys.extensionFor("IMAGE/PNG")).isEqualTo("png");
    }

    @Test
    @DisplayName("extensionFor sanitises an unknown subtype and never yields a separator")
    void extensionForUnknown() {
        assertThat(StorageKeys.extensionFor("image/svg+xml")).isEqualTo("svg");
        assertThat(StorageKeys.extensionFor("application/pdf")).isEqualTo("pdf");
        assertThat(StorageKeys.extensionFor("../../etc/passwd")).doesNotContain("/").doesNotContain(".");
        assertThat(StorageKeys.extensionFor(null)).isEqualTo("bin");
        assertThat(StorageKeys.extensionFor("")).isEqualTo("bin");
    }

    @Test
    @DisplayName("contentTypeForKey reverses the extension")
    void contentTypeForKey() {
        assertThat(StorageKeys.contentTypeForKey("member-photos/a.png")).isEqualTo("image/png");
        assertThat(StorageKeys.contentTypeForKey("x/y.jpg")).isEqualTo("image/jpeg");
        assertThat(StorageKeys.contentTypeForKey("x/y.jpeg")).isEqualTo("image/jpeg");
        assertThat(StorageKeys.contentTypeForKey("x/y.webp")).isEqualTo("image/webp");
        assertThat(StorageKeys.contentTypeForKey("x/y.zzz")).isEqualTo("application/octet-stream");
        assertThat(StorageKeys.contentTypeForKey("noextension")).isEqualTo("application/octet-stream");
        assertThat(StorageKeys.contentTypeForKey(null)).isEqualTo("application/octet-stream");
    }
}
