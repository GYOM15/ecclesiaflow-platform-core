package com.ecclesiaflow.platform.upload;

import java.util.Objects;
import java.util.Set;

/**
 * Declarative limits for one class of NON-image file upload, consumed by
 * {@link FileSanitizer}. Unlike images, these files cannot be re-encoded into a
 * safe canonical form, so the policy's job is strictly to bound the size and to
 * pin the accepted set of sniffed types.
 *
 * @param maxBytes     hard cap on the raw upload size; larger →
 *                     {@link UploadRejectedException.Reason#TOO_LARGE}
 * @param allowedTypes the sniffed media types accepted (declared types are never
 *                     consulted); anything else →
 *                     {@link UploadRejectedException.Reason#UNSUPPORTED_TYPE}
 */
public record FilePolicy(long maxBytes, Set<String> allowedTypes) {

    private static final long MB = 1024L * 1024L;

    public FilePolicy {
        Objects.requireNonNull(allowedTypes, "allowedTypes");
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        allowedTypes = Set.copyOf(allowedTypes);
    }

    /**
     * Bulk member import: a spreadsheet, either a CSV or an XLSX workbook, capped
     * at 5 MB. Only these two sniffed types are accepted — a file that merely
     * ends in {@code .csv} but is actually a binary or a non-workbook ZIP is
     * rejected.
     */
    public static FilePolicy spreadsheetImport() {
        return new FilePolicy(5 * MB, Set.of(MagicBytes.TEXT_CSV, MagicBytes.XLSX));
    }
}
