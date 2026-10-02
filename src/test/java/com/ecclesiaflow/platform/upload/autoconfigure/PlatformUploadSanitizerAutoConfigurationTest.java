package com.ecclesiaflow.platform.upload.autoconfigure;

import com.ecclesiaflow.platform.upload.FilePolicy;
import com.ecclesiaflow.platform.upload.FileSanitizer;
import com.ecclesiaflow.platform.upload.GatedImageDecoder;
import com.ecclesiaflow.platform.upload.ImageDecodeCapacityExceededException;
import com.ecclesiaflow.platform.upload.ImagePolicy;
import com.ecclesiaflow.platform.upload.ImageSanitizer;
import com.ecclesiaflow.platform.upload.SanitizedUpload;
import com.ecclesiaflow.platform.upload.UploadProperties;
import com.ecclesiaflow.platform.upload.logging.UploadSanitizerLoggingAspect;
import nl.altindag.log.LogCaptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformUploadSanitizerAutoConfigurationTest {

    private static final String[] TWO_SLOTS_NO_WAIT = {
            "ecclesiaflow.platform.upload.image.max-concurrent-decodes=2",
            "ecclesiaflow.platform.upload.image.decode-wait=0s"};

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AopAutoConfiguration.class, PlatformUploadSanitizerAutoConfiguration.class));

    @Test
    @DisplayName("the sanitizers are advised by the logging aspect in a real context")
    void sanitizersAreAdvised() {
        try (LogCaptor logs = LogCaptor.forClass(UploadSanitizerLoggingAspect.class)) {
            logs.setLogLevelToDebug();

            runner.run(context -> {
                assertThat(context).hasSingleBean(UploadSanitizerLoggingAspect.class);
                assertThat(AopUtils.isAopProxy(context.getBean(ImageSanitizer.class))).isTrue();
                context.getBean(FileSanitizer.class).sanitizeFile(
                        "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8), FilePolicy.spreadsheetImport());
            });

            assertThat(logs.getDebugLogs()).singleElement().asString()
                    .startsWith("UPLOAD-SANITIZE(file): accepted text/csv");
        }
    }

    @Test
    @DisplayName("the logging can be switched off without losing the sanitizers")
    void loggingCanBeSwitchedOff() {
        runner.withPropertyValues("ecclesiaflow.platform.upload.logging.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(UploadSanitizerLoggingAspect.class);
                    assertThat(context).hasSingleBean(ImageSanitizer.class).hasSingleBean(FileSanitizer.class);
                    assertThat(AopUtils.isAopProxy(context.getBean(ImageSanitizer.class))).isFalse();
                });
    }

    @Test
    @DisplayName("left unset, images are decoded one at a time with a five-second wait")
    void theDecodeDefaultsApply() {
        runner.run(context -> {
            UploadProperties.Image image = context.getBean(UploadProperties.class).getImage();
            assertThat(image.getMaxConcurrentDecodes()).isEqualTo(ImageSanitizer.DEFAULT_MAX_CONCURRENT_DECODES);
            assertThat(image.getDecodeWait()).isEqualTo(Duration.ofSeconds(5));
        });
    }

    @Test
    @DisplayName("the decode bound and wait set under ecclesiaflow.platform.upload.image reach the sanitizer")
    void theDecodeBoundIsConfigurable() {
        runner.withPropertyValues(TWO_SLOTS_NO_WAIT)
                .run(context -> assertTwoSlotsAndNoWait(context.getBean(ImageSanitizer.class)));
    }

    @Test
    @DisplayName("a module whose component scan covers the library still gets the configured sanitizer")
    void aScanningModuleGetsTheConfiguredSanitizer() {
        runner.withUserConfiguration(ScanningModule.class)
                .withPropertyValues(TWO_SLOTS_NO_WAIT)
                .run(context -> {
                    assertThat(context).hasSingleBean(ImageSanitizer.class);
                    assertTwoSlotsAndNoWait(context.getBean(ImageSanitizer.class));
                });
    }

    private static void assertTwoSlotsAndNoWait(ImageSanitizer sanitizer) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (GatedImageDecoder decoder = GatedImageDecoder.install()) {
            byte[] image = decoder.markedPng();
            List<Future<SanitizedUpload>> held = List.of(
                    pool.submit(() -> sanitizer.sanitize(image, ImagePolicy.avatar())),
                    pool.submit(() -> sanitizer.sanitize(image, ImagePolicy.avatar())));
            assertThat(decoder.awaitInFlight(2, Duration.ofSeconds(5))).isTrue();

            assertThatThrownBy(() -> sanitizer.sanitize(image, ImagePolicy.avatar()))
                    .isInstanceOf(ImageDecodeCapacityExceededException.class)
                    .hasMessage("all 2 image decode slots stayed busy for 0 ms");

            decoder.open();
            for (Future<SanitizedUpload> upload : held) {
                assertThat(upload.get(5, TimeUnit.SECONDS)).isNotNull();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // As @SpringBootApplication scans: the members module's root package covers the library's.
    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = ImageSanitizer.class,
            excludeFilters = @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class))
    static class ScanningModule {
    }

    @Test
    @DisplayName("an unusable decode bound stops the application at startup, not at the first upload")
    void anUnusableDecodeBoundFailsStartup() {
        runner.withPropertyValues("ecclesiaflow.platform.upload.image.max-concurrent-decodes=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("maxConcurrentDecodes");
                });
    }
}
