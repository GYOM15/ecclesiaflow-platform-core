package com.ecclesiaflow.platform.upload;

import java.util.Objects;
import java.util.Set;

public record FilePolicy(long maxBytes, Set<String> allowedTypes) {

    private static final long MB = 1024L * 1024L;

    public FilePolicy {
        Objects.requireNonNull(allowedTypes, "allowedTypes");
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        allowedTypes = Set.copyOf(allowedTypes);
    }

    public static FilePolicy spreadsheetImport() {
        return new FilePolicy(5 * MB, Set.of(MagicBytes.TEXT_CSV, MagicBytes.XLSX));
    }
}
