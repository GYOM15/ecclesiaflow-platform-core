package com.ecclesiaflow.platform.upload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MagicBytes - content sniffing ignores the declared type")
class MagicBytesTest {

    @Test
    @DisplayName("null / empty bytes are unknown")
    void nullOrEmpty() {
        assertThat(MagicBytes.detect(null)).isEmpty();
        assertThat(MagicBytes.detect(new byte[0])).isEmpty();
    }

    @Test
    @DisplayName("JPEG magic is detected as image/jpeg")
    void jpeg() {
        byte[] bytes = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0};
        assertThat(MagicBytes.detect(bytes)).contains(MagicBytes.IMAGE_JPEG);
    }

    @Test
    @DisplayName("PNG magic is detected as image/png")
    void png() {
        byte[] bytes = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
        assertThat(MagicBytes.detect(bytes)).contains(MagicBytes.IMAGE_PNG);
    }

    @Test
    @DisplayName("RIFF....WEBP is detected as image/webp")
    void webp() {
        byte[] bytes = new byte[]{
                'R', 'I', 'F', 'F', 0x10, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '};
        assertThat(MagicBytes.detect(bytes)).contains(MagicBytes.IMAGE_WEBP);
    }

    @Test
    @DisplayName("RIFF without the WEBP tag is not a webp")
    void riffButNotWebp() {
        byte[] wav = new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E'};
        assertThat(MagicBytes.detect(wav)).isNotEqualTo(java.util.Optional.of(MagicBytes.IMAGE_WEBP));
    }

    @Test
    @DisplayName("GIF87a and GIF89a are detected as image/gif")
    void gif() {
        assertThat(MagicBytes.detect("GIF87a....".getBytes(StandardCharsets.US_ASCII)))
                .contains(MagicBytes.IMAGE_GIF);
        assertThat(MagicBytes.detect("GIF89a....".getBytes(StandardCharsets.US_ASCII)))
                .contains(MagicBytes.IMAGE_GIF);
    }

    @Test
    @DisplayName("ZIP with an xl/ entry is detected as XLSX")
    void xlsx() throws Exception {
        // A ZIP whose local-file-header names an "xl/workbook.xml" entry — the
        // signature of an OOXML spreadsheet. We build a real (if minimal) ZIP.
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(baos)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("xl/workbook.xml"));
            zip.write("<workbook/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertThat(MagicBytes.detect(baos.toByteArray())).contains(MagicBytes.XLSX);
    }

    @Test
    @DisplayName("a ZIP without an xl/ entry is NOT an XLSX (unknown)")
    void zipButNotXlsx() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(baos)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("word/document.xml")); // a DOCX, say
            zip.write("<doc/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertThat(MagicBytes.detect(baos.toByteArray())).isEmpty();
    }

    @Test
    @DisplayName("plain UTF-8 text with no NUL falls back to text/csv")
    void csv() {
        byte[] bytes = "name,email\nAda,ada@example.org\n".getBytes(StandardCharsets.UTF_8);
        assertThat(MagicBytes.detect(bytes)).contains(MagicBytes.TEXT_CSV);
    }

    @Test
    @DisplayName("bytes containing a NUL are not treated as text")
    void nulIsBinary() {
        byte[] bytes = {'a', 'b', 0x00, 'c'};
        assertThat(MagicBytes.detect(bytes)).isEmpty();
    }

    @Test
    @DisplayName("invalid UTF-8 (a lone continuation byte) is not treated as text")
    void invalidUtf8() {
        byte[] bytes = {(byte) 0x80, (byte) 0x81, (byte) 0x82};
        assertThat(MagicBytes.detect(bytes)).isEmpty();
    }
}
