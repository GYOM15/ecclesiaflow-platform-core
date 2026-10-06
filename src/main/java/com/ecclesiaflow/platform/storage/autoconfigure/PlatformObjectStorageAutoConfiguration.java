package com.ecclesiaflow.platform.storage.autoconfigure;

import com.ecclesiaflow.platform.storage.FilesystemObjectStorage;
import com.ecclesiaflow.platform.storage.ObjectStorage;
import com.ecclesiaflow.platform.storage.ObjectStorageException;
import com.ecclesiaflow.platform.storage.ObjectStorageProperties;
import com.ecclesiaflow.platform.storage.s3.S3ObjectStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.util.StringUtils;

/**
 * An unset provider creates no adapter, so a module that injects {@link ObjectStorage} without
 * configuring it fails at startup.
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(ObjectStorageProperties.class)
public class PlatformObjectStorageAutoConfiguration {

    private static final String PRODUCTION_PROFILE = "prod";

    @Bean
    @ConditionalOnMissingBean(ObjectStorage.class)
    @ConditionalOnProperty(name = "ecclesiaflow.object-storage.provider", havingValue = "filesystem")
    public ObjectStorage filesystemObjectStorage(ObjectStorageProperties properties, Environment environment) {
        String basePath = properties.getFilesystem().getBasePath();
        // A blank base path means the temporary directory, which a container
        // restart wipes: in production that is silent data loss.
        if (!StringUtils.hasText(basePath) && environment.acceptsProfiles(Profiles.of(PRODUCTION_PROFILE))) {
            throw new ObjectStorageException(
                    "ecclesiaflow.object-storage.filesystem.base-path must be set when provider=filesystem"
                            + " under the prod profile");
        }
        FilesystemObjectStorage storage = new FilesystemObjectStorage(basePath);
        log.info("OBJECT-STORAGE: filesystem adapter active, base={}", storage.baseDir());
        return storage;
    }

    /** Isolated so the AWS SDK classes load only when {@code software.amazon.awssdk:s3} is present. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "software.amazon.awssdk.services.s3.S3Client")
    static class S3StorageConfiguration {

        @Bean
        @ConditionalOnMissingBean(ObjectStorage.class)
        @ConditionalOnProperty(name = "ecclesiaflow.object-storage.provider", havingValue = "s3")
        public ObjectStorage s3ObjectStorage(ObjectStorageProperties properties) {
            S3ObjectStorage storage = new S3ObjectStorage(properties.getS3());
            // The bucket only: the endpoint names the account and the keys are secrets.
            log.info("OBJECT-STORAGE: s3 adapter active, bucket={}", properties.getS3().getBucket());
            return storage;
        }
    }
}
