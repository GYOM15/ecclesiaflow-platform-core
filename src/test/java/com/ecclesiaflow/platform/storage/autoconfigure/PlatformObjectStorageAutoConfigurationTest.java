package com.ecclesiaflow.platform.storage.autoconfigure;

import com.ecclesiaflow.platform.storage.FilesystemObjectStorage;
import com.ecclesiaflow.platform.storage.ObjectStorage;
import com.ecclesiaflow.platform.storage.s3.S3ObjectStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PlatformObjectStorageAutoConfiguration - single-bean provider selection")
class PlatformObjectStorageAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformObjectStorageAutoConfiguration.class));

    @Test
    @DisplayName("defaults to the filesystem adapter when no provider is set")
    void defaultsToFilesystem() {
        runner.run(context -> assertThat(context)
                .getBean(ObjectStorage.class)
                .isInstanceOf(FilesystemObjectStorage.class));
    }

    @Test
    @DisplayName("provider=filesystem selects the filesystem adapter")
    void explicitFilesystem() {
        runner.withPropertyValues("ecclesiaflow.object-storage.provider=filesystem")
                .run(context -> assertThat(context)
                        .getBean(ObjectStorage.class)
                        .isInstanceOf(FilesystemObjectStorage.class));
    }

    @Test
    @DisplayName("provider=s3 with settings selects the S3 adapter (only one ObjectStorage bean)")
    void s3WhenConfigured() {
        runner.withPropertyValues(
                        "ecclesiaflow.object-storage.provider=s3",
                        "ecclesiaflow.object-storage.s3.endpoint=https://acct.r2.cloudflarestorage.com",
                        "ecclesiaflow.object-storage.s3.access-key-id=key",
                        "ecclesiaflow.object-storage.s3.secret-access-key=secret",
                        "ecclesiaflow.object-storage.s3.bucket=ecclesiaflow-media")
                .run(context -> {
                    assertThat(context).hasSingleBean(ObjectStorage.class);
                    assertThat(context).getBean(ObjectStorage.class).isInstanceOf(S3ObjectStorage.class);
                });
    }
}
