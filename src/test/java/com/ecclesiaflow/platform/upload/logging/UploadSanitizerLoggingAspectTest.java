package com.ecclesiaflow.platform.upload.logging;

import com.ecclesiaflow.platform.upload.FilePolicy;
import com.ecclesiaflow.platform.upload.FileSanitizer;
import com.ecclesiaflow.platform.upload.ImagePolicy;
import com.ecclesiaflow.platform.upload.ImageSanitizer;
import com.ecclesiaflow.platform.upload.SanitizedUpload;
import com.ecclesiaflow.platform.upload.UploadRejectedException;
import nl.altindag.log.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadSanitizerLoggingAspectTest {

    private final LogCaptor logs = LogCaptor.forClass(UploadSanitizerLoggingAspect.class);

    @BeforeEach
    void setUp() {
        logs.setLogLevelToDebug();
    }

    @AfterEach
    void tearDown() {
        logs.close();
    }

    @Test
    @DisplayName("a sanitized image is traced with its detected input and its output, never its content")
    void tracesASanitizedImage() throws Exception {
        byte[] png = png();

        SanitizedUpload result = advised(new ImageSanitizer()).sanitize(png, ImagePolicy.avatar());

        assertThat(logs.getDebugLogs()).singleElement().asString()
                .isEqualTo("UPLOAD-SANITIZE: image/png (" + png.length + " bytes) -> image/jpeg ("
                        + result.size() + " bytes)");
    }

    @Test
    @DisplayName("an accepted file is traced with its detected type and size")
    void tracesAnAcceptedFile() {
        byte[] csv = "name,email\nAda,ada@example.org\n".getBytes(StandardCharsets.UTF_8);

        advised(new FileSanitizer()).sanitizeFile(csv, FilePolicy.spreadsheetImport());

        assertThat(logs.getDebugLogs()).singleElement().asString()
                .isEqualTo("UPLOAD-SANITIZE(file): accepted text/csv (" + csv.length + " bytes)")
                .doesNotContain("ada@example.org");
    }

    @Test
    @DisplayName("with DEBUG off nothing is written")
    void staysQuietAboveDebug() throws Exception {
        logs.setLogLevelToInfo();

        advised(new ImageSanitizer()).sanitize(png(), ImagePolicy.avatar());

        assertThat(logs.getLogs()).isEmpty();
    }

    @Test
    @DisplayName("a rejection propagates untouched and is left to the caller's boundary to log")
    void leavesRejectionsToTheCaller() {
        ImageSanitizer sanitizer = advised(new ImageSanitizer());
        byte[] text = "not an image".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> sanitizer.sanitize(text, ImagePolicy.avatar()))
                .isInstanceOf(UploadRejectedException.class);
        assertThat(logs.getLogs()).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static <T> T advised(T target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new UploadSanitizerLoggingAspect());
        return (T) factory.getProxy();
    }

    private static byte[] png() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }
}
