package com.ecclesiaflow.platform.upload;

import com.ecclesiaflow.platform.upload.text.TextUploadDecoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Validates a non-image upload (CSV, XLSX) before it is stored or parsed: a size cap, and the sniffed
 * type returned as the content type, so a spoofed {@code report.csv} is refused. A spreadsheet cannot be
 * re-rendered like an image, so its parser must still run defensively. An XLSX is returned unchanged; a
 * CSV comes back as UTF-8 without a BOM, whatever its source encoding.
 */
@Component
public class FileSanitizer {

    public SanitizedUpload sanitizeFile(byte[] bytes, FilePolicy policy) {
        if (bytes == null) {
            throw new UploadRejectedException(
                    UploadRejectedException.Reason.UNSUPPORTED_TYPE, "no upload bytes");
        }
        if (bytes.length > policy.maxBytes()) {
            throw new UploadRejectedException(
                    UploadRejectedException.Reason.TOO_LARGE,
                    "upload is " + bytes.length + " bytes, exceeds cap of " + policy.maxBytes());
        }

        String detected = MagicBytes.detect(bytes).orElse(null);
        if (detected == null || !policy.allowedTypes().contains(detected)) {
            throw new UploadRejectedException(
                    UploadRejectedException.Reason.UNSUPPORTED_TYPE,
                    "detected media type " + detected + " is not an accepted file type");
        }

        byte[] data = MagicBytes.TEXT_CSV.equals(detected)
                ? TextUploadDecoder.decode(bytes).map(text -> text.getBytes(StandardCharsets.UTF_8)).orElse(bytes)
                : bytes;
        return new SanitizedUpload(data, detected, data.length);
    }
}
