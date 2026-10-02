package com.ecclesiaflow.platform.upload;

import com.ecclesiaflow.platform.upload.text.TextUploadDecoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Validates an untrusted NON-image file upload (today: CSV / XLSX spreadsheet
 * imports) before it is stored or parsed. Modules MUST route such uploads
 * through this component first.
 *
 * <p>A spreadsheet cannot be re-rendered into a safe canonical form the way an
 * image can, so this sanitizer does two things that a naive size-plus-declared-
 * type check does not:</p>
 * <ul>
 *   <li>bounds the size (a cheap guard, also a mild bomb guard for XLSX which is
 *       a ZIP), and</li>
 *   <li>pins the <em>real</em>, sniffed media type ({@link MagicBytes}) and
 *       returns THAT as the content type — so a spoofed {@code report.csv} that
 *       is actually an executable or a non-workbook ZIP is refused, and the type
 *       persisted downstream is never the client's word.</li>
 * </ul>
 *
 * <p>An XLSX is returned unchanged. A CSV is returned as UTF-8 without a BOM,
 * whatever its source encoding (UTF-8 or Windows-1252, see
 * {@link TextUploadDecoder}), so the caller always decodes it as UTF-8. It is the
 * caller's parser (e.g. the POI / CSV reader) that must itself be run
 * defensively. This component guarantees the bytes are of an allowed type and
 * within the size cap, not that their internal content is benign.</p>
 */
@Component
public class FileSanitizer {

    /**
     * Validates a non-image upload against {@code policy}.
     *
     * @param bytes  the raw upload bytes exactly as received (declared type is
     *               ignored — the real type is sniffed here)
     * @param policy the size cap and accepted-type set for this upload class
     * @return a {@link SanitizedUpload} carrying the bytes (a CSV re-encoded as
     *         UTF-8) and the <em>detected</em> content type
     * @throws UploadRejectedException if the upload exceeds the size cap
     *         ({@link UploadRejectedException.Reason#TOO_LARGE}) or its sniffed
     *         type is not allowed
     *         ({@link UploadRejectedException.Reason#UNSUPPORTED_TYPE})
     */
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
        // Return the DETECTED type, never the client-declared one.
        return new SanitizedUpload(data, detected, data.length);
    }
}
