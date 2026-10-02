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
 * Auto-configures a single {@link ObjectStorage} bean from
 * {@code ecclesiaflow.object-storage.provider}, so any module on platform-core
 * gets image storage without an explicit {@code @Import}.
 *
 * <ul>
 *   <li>{@code provider=filesystem} → {@link FilesystemObjectStorage}, for dev
 *       and tests; under the {@code prod} profile it requires an explicit
 *       {@code filesystem.base-path};</li>
 *   <li>{@code provider=s3} → {@link S3ObjectStorage} (Cloudflare R2 / S3),
 *       only when the AWS S3 SDK is on the classpath;</li>
 *   <li>unset → no adapter, so a module that injects {@link ObjectStorage}
 *       without configuring it fails at startup.</li>
 * </ul>
 *
 * <p>Both beans are {@code @ConditionalOnMissingBean(ObjectStorage.class)} so a
 * module can still supply its own adapter and win.</p>
 *
 * <p>The adapters themselves never log; this class announces, once at startup,
 * which one it activated.</p>
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

    /**
     * Isolated so the S3 adapter class (and its AWS SDK imports) is only loaded
     * when {@code software.amazon.awssdk:s3} is present — modules that don't
     * store images never pull the SDK.
     */
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
