package com.ecclesiaflow.platform.upload.text;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Decodes an uploaded text file whose encoding the client never declares.
 *
 * <p>The rule is deterministic: a UTF-8 BOM means UTF-8; otherwise bytes that
 * are valid UTF-8 are read as UTF-8; anything else is read as Windows-1252, the
 * encoding of Excel's « CSV (séparateur : point-virgule) » export on a French
 * Windows. Bytes that fit none of these, or that carry a NUL or a control
 * character other than tab, CR and LF, are not text.</p>
 */
public final class TextUploadDecoder {

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private TextUploadDecoder() {
    }

    /**
     * @return the decoded text without any BOM, or {@link Optional#empty()} when
     *         the bytes are not text in a supported encoding
     */
    public static Optional<String> decode(byte[] bytes) {
        if (bytes == null || containsNul(bytes)) {
            return Optional.empty();
        }
        boolean bom = startsWith(bytes, UTF8_BOM);
        Optional<String> utf8 = strictDecode(bytes, bom ? UTF8_BOM.length : 0, StandardCharsets.UTF_8);
        if (utf8.isPresent() || bom) {
            return utf8;
        }
        // Windows-1252 maps all but five byte values, so without this filter any
        // NUL-free binary would pass for text.
        return strictDecode(bytes, 0, WINDOWS_1252).filter(TextUploadDecoder::hasNoControlCharacter);
    }

    private static Optional<String> strictDecode(byte[] bytes, int offset, Charset charset) {
        try {
            return Optional.of(charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                    .toString());
        } catch (CharacterCodingException e) {
            return Optional.empty();
        }
    }

    private static boolean containsNul(byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0x00) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasNoControlCharacter(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c) && c != '\t' && c != '\n' && c != '\r') {
                return false;
            }
        }
        return true;
    }
}
