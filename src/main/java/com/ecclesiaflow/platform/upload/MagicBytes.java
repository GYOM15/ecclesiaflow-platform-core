package com.ecclesiaflow.platform.upload;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Content-sniffing helper: derives the <strong>real</strong> media type of an
 * upload from its leading bytes (and, for XLSX, its internal structure).
 *
 * <p>Security rule #1 of upload handling: <em>never trust the client-declared
 * {@code Content-Type} or file extension</em>. A {@code .png} can be a ZIP, a
 * shell script, or a polyglot that is simultaneously a valid image and a valid
 * HTML/JS payload. This class ignores whatever the client said and looks only at
 * the bytes, so the sanitizers can allow/deny on ground truth.</p>
 *
 * <p>Detected types:</p>
 * <ul>
 *   <li>{@code image/jpeg} — {@code FF D8 FF}</li>
 *   <li>{@code image/png} — {@code 89 50 4E 47 0D 0A 1A 0A}</li>
 *   <li>{@code image/webp} — {@code "RIFF" xxxx "WEBP"}</li>
 *   <li>{@code image/gif} — {@code GIF87a} / {@code GIF89a}</li>
 *   <li>{@code application/vnd.openxmlformats-officedocument.spreadsheetml.sheet}
 *       (XLSX) — the ZIP local-header magic {@code 50 4B 03 04} AND an
 *       {@code "xl/"} entry name somewhere in the bytes (what distinguishes an
 *       XLSX workbook from any other ZIP such as a DOCX or a JAR).</li>
 *   <li>{@code text/csv} — the fallback: decodes cleanly as strict UTF-8 and
 *       contains no NUL byte, and matched none of the binary signatures above.</li>
 * </ul>
 *
 * <p>Anything else — including a ZIP that is not an XLSX, or binary junk that is
 * not valid UTF-8 — returns {@link Optional#empty()} (unknown/unsupported).</p>
 */
public final class MagicBytes {

    // Canonical MIME types this platform recognises. Exposed so policies and
    // callers reference one constant instead of duplicating literals.
    public static final String IMAGE_JPEG = "image/jpeg";
    public static final String IMAGE_PNG = "image/png";
    public static final String IMAGE_WEBP = "image/webp";
    public static final String IMAGE_GIF = "image/gif";
    public static final String XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    public static final String TEXT_CSV = "text/csv";

    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_MAGIC =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] RIFF_MAGIC = {'R', 'I', 'F', 'F'};
    private static final byte[] WEBP_TAG = {'W', 'E', 'B', 'P'};
    private static final byte[] GIF87A_MAGIC = {'G', 'I', 'F', '8', '7', 'a'};
    private static final byte[] GIF89A_MAGIC = {'G', 'I', 'F', '8', '9', 'a'};
    private static final byte[] ZIP_LOCAL_HEADER = {0x50, 0x4B, 0x03, 0x04};
    private static final byte[] XL_ENTRY = {'x', 'l', '/'};

    private MagicBytes() {
    }

    /**
     * Sniffs the media type of {@code bytes} from its content.
     *
     * @param bytes the raw upload bytes (a {@code null} or empty array yields
     *              {@link Optional#empty()} — there is nothing to identify)
     * @return the detected canonical MIME type, or {@link Optional#empty()} when
     *         the content matches no supported type
     */
    public static Optional<String> detect(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return Optional.empty();
        }
        if (startsWith(bytes, JPEG_MAGIC)) {
            return Optional.of(IMAGE_JPEG);
        }
        if (startsWith(bytes, PNG_MAGIC)) {
            return Optional.of(IMAGE_PNG);
        }
        // WebP: "RIFF" at 0, then a 4-byte chunk size, then "WEBP" at offset 8.
        if (startsWith(bytes, RIFF_MAGIC) && regionEquals(bytes, 8, WEBP_TAG)) {
            return Optional.of(IMAGE_WEBP);
        }
        if (startsWith(bytes, GIF87A_MAGIC) || startsWith(bytes, GIF89A_MAGIC)) {
            return Optional.of(IMAGE_GIF);
        }
        // XLSX is a ZIP whose entries include the "xl/" workbook folder; the
        // ZIP local-header stores entry names uncompressed, so "xl/" appears as
        // literal bytes even without unzipping.
        if (startsWith(bytes, ZIP_LOCAL_HEADER) && contains(bytes, XL_ENTRY)) {
            return Optional.of(XLSX);
        }
        // Fallback: treat as CSV/text only if it is genuinely textual — strict
        // UTF-8 and free of NUL bytes (a NUL is the classic "this is binary"
        // tell). This deliberately runs last so no binary above is mislabelled.
        if (isProbablyUtf8Text(bytes)) {
            return Optional.of(TEXT_CSV);
        }
        return Optional.empty();
    }

    /** @return {@code true} iff {@code data} begins with the bytes {@code prefix}. */
    private static boolean startsWith(byte[] data, byte[] prefix) {
        return regionEquals(data, 0, prefix);
    }

    /** @return {@code true} iff {@code needle} occurs at {@code offset} in {@code data}. */
    private static boolean regionEquals(byte[] data, int offset, byte[] needle) {
        if (offset < 0 || data.length < offset + needle.length) {
            return false;
        }
        for (int i = 0; i < needle.length; i++) {
            if (data[offset + i] != needle[i]) {
                return false;
            }
        }
        return true;
    }

    /** @return {@code true} iff {@code needle} occurs anywhere in {@code data}. */
    private static boolean contains(byte[] data, byte[] needle) {
        if (needle.length == 0 || data.length < needle.length) {
            return false;
        }
        int last = data.length - needle.length;
        outer:
        for (int i = 0; i <= last; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * @return {@code true} iff the bytes decode as strict UTF-8 (rejecting any
     *         malformed or unmappable sequence) and contain no NUL (0x00) byte.
     */
    private static boolean isProbablyUtf8Text(byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0x00) {
                return false;
            }
        }
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }
}
