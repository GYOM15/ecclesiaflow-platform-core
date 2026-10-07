package com.ecclesiaflow.platform.upload.text;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TextUploadDecoder - UTF-8 first, Windows-1252 fallback")
class TextUploadDecoderTest {

    @Nested
    @DisplayName("UTF-8")
    class Utf8 {

        @Test
        @DisplayName("valid UTF-8 decodes as UTF-8")
        void validUtf8() {
            byte[] bytes = "Prénom;Cœur;10 €\n".getBytes(StandardCharsets.UTF_8);

            assertThat(TextUploadDecoder.decode(bytes)).contains("Prénom;Cœur;10 €\n");
        }

        @Test
        @DisplayName("a UTF-8 BOM is stripped")
        void bomStripped() {
            byte[] bytes = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'a', ';', (byte) 0xC3, (byte) 0xA9};

            assertThat(TextUploadDecoder.decode(bytes)).contains("a;é");
        }

        @Test
        @DisplayName("a UTF-8 BOM followed by invalid UTF-8 is not text (no Windows-1252 fallback)")
        void bomWithInvalidBody() {
            byte[] bytes = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'a', (byte) 0xE9, 'b'};

            assertThat(TextUploadDecoder.decode(bytes)).isEmpty();
        }

        @Test
        @DisplayName("input shorter than a BOM still decodes")
        void shorterThanBom() {
            assertThat(TextUploadDecoder.decode(new byte[]{'a', 'b'})).contains("ab");
            assertThat(TextUploadDecoder.decode(new byte[0])).contains("");
        }
    }

    @Nested
    @DisplayName("Windows-1252")
    class Windows1252 {

        @Test
        @DisplayName("é è ç œ € in single-byte cp1252 decode without mojibake")
        void accentsDecoded() {
            byte[] bytes = {
                    (byte) 0xE9, (byte) 0xE8, (byte) 0xE7, (byte) 0x9C, (byte) 0x80, '\t', '\r', '\n'};

            assertThat(TextUploadDecoder.decode(bytes)).contains("éèçœ€\t\r\n");
        }

        @Test
        @DisplayName("a byte undefined in cp1252 is not text")
        void undefinedByte() {
            assertThat(TextUploadDecoder.decode(new byte[]{'a', (byte) 0xE9, (byte) 0x9D})).isEmpty();
        }

        @Test
        @DisplayName("a C0 control character makes the bytes binary")
        void c0Control() {
            assertThat(TextUploadDecoder.decode(new byte[]{'a', (byte) 0xE9, 0x1B})).isEmpty();
        }

        @Test
        @DisplayName("DEL makes the bytes binary")
        void delControl() {
            assertThat(TextUploadDecoder.decode(new byte[]{'a', (byte) 0xE9, 0x7F})).isEmpty();
        }
    }

    @Test
    @DisplayName("null and NUL-bearing input are not text")
    void nullAndNul() {
        assertThat(TextUploadDecoder.decode(null)).isEmpty();
        assertThat(TextUploadDecoder.decode(new byte[]{'a', 0x00, 'b'})).isEmpty();
    }
}
