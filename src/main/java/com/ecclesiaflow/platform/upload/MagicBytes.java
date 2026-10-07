package com.ecclesiaflow.platform.upload;

import com.ecclesiaflow.platform.upload.text.TextUploadDecoder;

import java.util.Optional;

/**
 * Derives the real media type of an upload from its bytes; the client's {@code Content-Type} and file
 * extension are never trusted (a {@code .png} can be a ZIP, or an image and HTML polyglot). CSV is the
 * fallback for content that matches no binary signature and decodes as text; a ZIP that is not an XLSX
 * is unknown.
 */
public final class MagicBytes {

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
        // Entry names are stored uncompressed, so the "xl/" folder that sets an XLSX apart from
        // other ZIPs shows as literal bytes.
        if (startsWith(bytes, ZIP_LOCAL_HEADER) && contains(bytes, XL_ENTRY)) {
            return Optional.of(XLSX);
        }
        // Runs last so that no binary format above is mislabelled as text.
        if (TextUploadDecoder.decode(bytes).isPresent()) {
            return Optional.of(TEXT_CSV);
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        return regionEquals(data, 0, prefix);
    }

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
}
