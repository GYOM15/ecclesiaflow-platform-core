package com.ecclesiaflow.platform.upload.autoconfigure;

import com.ecclesiaflow.platform.upload.FileSanitizer;
import com.ecclesiaflow.platform.upload.ImageSanitizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-registers the upload sanitizers so any module on platform-core can simply
 * inject {@link ImageSanitizer} / {@link FileSanitizer} without an explicit
 * {@code @Import} or component scan of the library package — the same pattern the
 * object-storage adapters use.
 *
 * <p>Gated on {@code javax.imageio.ImageIO} being present (it always is on a
 * server JRE) so the configuration never fails to load on a headless-stripped
 * runtime that lacks {@code java.desktop}. Both beans are
 * {@code @ConditionalOnMissingBean} so a module can still supply its own.</p>
 */
@AutoConfiguration
@ConditionalOnClass(name = "javax.imageio.ImageIO")
public class PlatformUploadSanitizerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ImageSanitizer imageSanitizer() {
        return new ImageSanitizer();
    }

    @Bean
    @ConditionalOnMissingBean
    public FileSanitizer fileSanitizer() {
        return new FileSanitizer();
    }
}
