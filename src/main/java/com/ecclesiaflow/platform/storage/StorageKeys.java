package com.ecclesiaflow.platform.storage;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Shared by every adapter, so a filesystem and an S3 object built from the same inputs get the same key. */
public final class StorageKeys {

    private static final Map<String, String> EXT_BY_TYPE = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp",
            "image/gif", "gif");

    private static final Map<String, String> TYPE_BY_EXT = Map.of(
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png",
            "webp", "image/webp",
            "gif", "image/gif");

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private StorageKeys() {
    }

    /** Strips the prefix's surrounding slashes; the extension comes from {@code contentType}. */
    public static String newKey(String keyPrefix, String contentType) {
        String prefix = normalisePrefix(keyPrefix);
        return prefix + "/" + UUID.randomUUID() + "." + extensionFor(contentType);
    }

    /** Falls back to the sanitized subtype, then {@code "bin"}; never contains a path separator. */
    public static String extensionFor(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "bin";
        }
        String type = contentType.trim().toLowerCase(Locale.ROOT);
        String known = EXT_BY_TYPE.get(type);
        if (known != null) {
            return known;
        }
        int slash = type.indexOf('/');
        String subtype = slash >= 0 ? type.substring(slash + 1) : type;
        int plus = subtype.indexOf('+');
        if (plus >= 0) {
            subtype = subtype.substring(0, plus);
        }
        String sanitised = subtype.replaceAll("[^a-z0-9]", "");
        return sanitised.isBlank() ? "bin" : sanitised;
    }

    public static String contentTypeForKey(String key) {
        if (key == null) {
            return DEFAULT_CONTENT_TYPE;
        }
        int dot = key.lastIndexOf('.');
        if (dot < 0 || dot == key.length() - 1) {
            return DEFAULT_CONTENT_TYPE;
        }
        String ext = key.substring(dot + 1).toLowerCase(Locale.ROOT);
        return TYPE_BY_EXT.getOrDefault(ext, DEFAULT_CONTENT_TYPE);
    }

    private static String normalisePrefix(String keyPrefix) {
        if (keyPrefix == null || keyPrefix.isBlank()) {
            throw new IllegalArgumentException("keyPrefix must not be blank");
        }
        String prefix = keyPrefix.trim().replaceAll("^/+", "").replaceAll("/+$", "");
        if (prefix.isBlank()) {
            throw new IllegalArgumentException("keyPrefix must not be blank");
        }
        return prefix;
    }
}
