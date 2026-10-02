package com.ecclesiaflow.platform.upload.autoconfigure;

import com.ecclesiaflow.platform.upload.FilePolicy;
import com.ecclesiaflow.platform.upload.FileSanitizer;
import com.ecclesiaflow.platform.upload.ImageSanitizer;
import com.ecclesiaflow.platform.upload.logging.UploadSanitizerLoggingAspect;
import nl.altindag.log.LogCaptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformUploadSanitizerAutoConfigurationTest {

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
}
