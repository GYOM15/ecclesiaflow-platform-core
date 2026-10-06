package com.ecclesiaflow.platform.upload.autoconfigure;

import com.ecclesiaflow.platform.upload.FileSanitizer;
import com.ecclesiaflow.platform.upload.ImageSanitizer;
import com.ecclesiaflow.platform.upload.UploadProperties;
import com.ecclesiaflow.platform.upload.logging.UploadSanitizerLoggingAspect;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** Gated on ImageIO so the configuration still loads on a runtime stripped of {@code java.desktop}. */
@AutoConfiguration
@ConditionalOnClass(name = "javax.imageio.ImageIO")
@EnableConfigurationProperties(UploadProperties.class)
public class PlatformUploadSanitizerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ImageSanitizer imageSanitizer(UploadProperties properties) {
        UploadProperties.Image image = properties.getImage();
        return new ImageSanitizer(image.getMaxConcurrentDecodes(), image.getDecodeWait());
    }

    @Bean
    @ConditionalOnMissingBean
    public FileSanitizer fileSanitizer() {
        return new FileSanitizer();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ecclesiaflow.platform.upload.logging", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public UploadSanitizerLoggingAspect uploadSanitizerLoggingAspect() {
        return new UploadSanitizerLoggingAspect();
    }
}
