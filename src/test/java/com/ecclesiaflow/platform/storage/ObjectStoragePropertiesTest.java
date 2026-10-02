package com.ecclesiaflow.platform.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The property names modules and the deploy compose set; renaming one silently drops its value. */
class ObjectStoragePropertiesTest {

    private static ObjectStorageProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate("ecclesiaflow.object-storage", ObjectStorageProperties.class);
    }

    @Test
    @DisplayName("every documented property binds to its field")
    void bindsTheDocumentedProperties() {
        ObjectStorageProperties props = bind(Map.of(
                "ecclesiaflow.object-storage.provider", "s3",
                "ecclesiaflow.object-storage.filesystem.base-path", "/data/objects",
                "ecclesiaflow.object-storage.s3.endpoint", "https://account.r2.cloudflarestorage.com",
                "ecclesiaflow.object-storage.s3.access-key-id", "key-id",
                "ecclesiaflow.object-storage.s3.secret-access-key", "secret",
                "ecclesiaflow.object-storage.s3.bucket", "ecclesiaflow-media",
                "ecclesiaflow.object-storage.s3.region", "eu-west-1",
                "ecclesiaflow.object-storage.s3.path-style-access", "false",
                "ecclesiaflow.object-storage.s3.public-base-url", "https://media.example.org"));

        assertThat(props.getProvider()).isEqualTo(ObjectStorageProperties.Provider.S3);
        assertThat(props.getFilesystem().getBasePath()).isEqualTo("/data/objects");
        ObjectStorageProperties.S3 s3 = props.getS3();
        assertThat(s3.getEndpoint()).isEqualTo("https://account.r2.cloudflarestorage.com");
        assertThat(s3.getAccessKeyId()).isEqualTo("key-id");
        assertThat(s3.getSecretAccessKey()).isEqualTo("secret");
        assertThat(s3.getBucket()).isEqualTo("ecclesiaflow-media");
        assertThat(s3.getRegion()).isEqualTo("eu-west-1");
        assertThat(s3.isPathStyleAccess()).isFalse();
        assertThat(s3.getPublicBaseUrl()).isEqualTo("https://media.example.org");
    }

    @Test
    @DisplayName("defaults: filesystem, R2-style addressing, no public base URL")
    void defaults() {
        ObjectStorageProperties props = bind(Map.of());

        assertThat(props.getProvider()).isEqualTo(ObjectStorageProperties.Provider.FILESYSTEM);
        assertThat(props.getFilesystem().getBasePath()).isEmpty();
        assertThat(props.getS3().getRegion()).isEqualTo("auto");
        assertThat(props.getS3().isPathStyleAccess()).isTrue();
        assertThat(props.getS3().getPublicBaseUrl()).isNull();
    }
}
