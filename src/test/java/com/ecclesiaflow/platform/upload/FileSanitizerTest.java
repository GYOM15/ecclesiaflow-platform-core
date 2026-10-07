package com.ecclesiaflow.platform.upload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
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

    @Nested
    @DisplayName("CSV text encoding")
    class CsvEncoding {

        private static final String TEXT =
                "Nom;Prénom;Email\r\nCœur;Hélène;h@ex.fr\r\nGarçon;Achille Lèvre;a@ex.fr\r\nDon;10 €;d@ex.fr\r\n";

        @Test
        @DisplayName("a Windows-1252 CSV is accepted and handed back as UTF-8 without mojibake")
        void windows1252TranscodedToUtf8() {
            byte[] cp1252 = TEXT.getBytes(Charset.forName("windows-1252"));
            // Real single-byte cp1252 octets, not UTF-8 sequences.
            assertThat(cp1252).contains((byte) 0xE9, (byte) 0xE8, (byte) 0xE7, (byte) 0x9C, (byte) 0x80);

            SanitizedUpload result = sanitizer.sanitizeFile(cp1252, FilePolicy.spreadsheetImport());

            assertThat(result.contentType()).isEqualTo(MagicBytes.TEXT_CSV);
            assertThat(new String(result.data(), StandardCharsets.UTF_8)).isEqualTo(TEXT);
            assertThat(result.size()).isEqualTo(result.data().length);
        }

        @Test
        @DisplayName("a UTF-8 CSV with a BOM is handed back as UTF-8 without the BOM")
        void utf8BomStripped() {
            byte[] body = TEXT.getBytes(StandardCharsets.UTF_8);
            byte[] withBom = new byte[body.length + 3];
            withBom[0] = (byte) 0xEF;
            withBom[1] = (byte) 0xBB;
            withBom[2] = (byte) 0xBF;
            System.arraycopy(body, 0, withBom, 3, body.length);

            SanitizedUpload result = sanitizer.sanitizeFile(withBom, FilePolicy.spreadsheetImport());

            assertThat(result.data()).isEqualTo(body);
            assertThat(result.size()).isEqualTo(body.length);
        }

        @Test
        @DisplayName("a UTF-8 CSV with accents is returned byte for byte")
        void utf8Unchanged() {
            byte[] utf8 = TEXT.getBytes(StandardCharsets.UTF_8);

            SanitizedUpload result = sanitizer.sanitizeFile(utf8, FilePolicy.spreadsheetImport());

            assertThat(result.data()).isEqualTo(utf8);
        }

        @Test
        @DisplayName("a byte undefined in Windows-1252 (0x81) is rejected UNSUPPORTED_TYPE")
        void undefinedWindows1252ByteRejected() {
            byte[] bytes = {'a', ';', (byte) 0xE9, (byte) 0x81, '\n'};

            assertThatThrownBy(() -> sanitizer.sanitizeFile(bytes, FilePolicy.spreadsheetImport()))
                    .isInstanceOf(UploadRejectedException.class)
                    .extracting(e -> ((UploadRejectedException) e).getReason())
                    .isEqualTo(UploadRejectedException.Reason.UNSUPPORTED_TYPE);
        }
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
