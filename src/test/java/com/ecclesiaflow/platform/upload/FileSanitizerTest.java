package com.ecclesiaflow.platform.upload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@DisplayName("FileSanitizer - non-image uploads validated by sniffed type")
class FileSanitizerTest {

    private final FileSanitizer sanitizer = new FileSanitizer();

    @Test
    @DisplayName("a CSV is accepted under spreadsheetImport() with the detected content type")
    void csvAccepted() {
        byte[] csv = "name,email\nAda,ada@example.org\n".getBytes(StandardCharsets.UTF_8);

        SanitizedUpload result = sanitizer.sanitizeFile(csv, FilePolicy.spreadsheetImport());

        assertThat(result.contentType()).isEqualTo(MagicBytes.TEXT_CSV);
        // Bytes are returned unchanged for non-image files.
        assertThat(result.data()).isEqualTo(csv);
        assertThat(result.size()).isEqualTo(csv.length);
    }

    @Test
    @DisplayName("an XLSX workbook is accepted under spreadsheetImport()")
    void xlsxAccepted() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(baos)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("xl/workbook.xml"));
            zip.write("<workbook/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        SanitizedUpload result = sanitizer.sanitizeFile(baos.toByteArray(), FilePolicy.spreadsheetImport());

        assertThat(result.contentType()).isEqualTo(MagicBytes.XLSX);
    }

    @Test
    @DisplayName("a PNG is rejected UNSUPPORTED_TYPE under spreadsheetImport()")
    void pngRejected() throws Exception {
        BufferedImage img = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(img, "png", out)).isTrue();

        UploadRejectedException ex = catchThrowableOfType(
                () -> sanitizer.sanitizeFile(out.toByteArray(), FilePolicy.spreadsheetImport()),
                UploadRejectedException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getReason()).isEqualTo(UploadRejectedException.Reason.UNSUPPORTED_TYPE);
    }

    @Test
    @DisplayName("an over-cap file is rejected TOO_LARGE")
    void oversizeRejected() {
        byte[] csv = "a,b,c\n1,2,3\n".getBytes(StandardCharsets.UTF_8);
        FilePolicy tiny = new FilePolicy(4, Set.of(MagicBytes.TEXT_CSV));

        assertThatThrownBy(() -> sanitizer.sanitizeFile(csv, tiny))
                .isInstanceOf(UploadRejectedException.class)
                .extracting(e -> ((UploadRejectedException) e).getReason())
                .isEqualTo(UploadRejectedException.Reason.TOO_LARGE);
    }

    @Test
    @DisplayName("null bytes are rejected")
    void nullRejected() {
        assertThatThrownBy(() -> sanitizer.sanitizeFile(null, FilePolicy.spreadsheetImport()))
                .isInstanceOf(UploadRejectedException.class);
    }
}
