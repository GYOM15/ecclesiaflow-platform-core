package com.ecclesiaflow.platform.storage;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Pure helpers shared by every {@link ObjectStorage} adapter so key generation
 * and MIME/extension mapping stay identical across providers (a filesystem
 * object and an S3 object built from the same inputs get the same key shape).
 *
 * <p>Keys are {@code <prefix>/<uuid>.<ext>}: random (never derived from a
 * client filename — path-traversal / collision safety) and content-addressed by
 * the UUID so the object is immutable and cacheable forever.</p>
 */
public final class StorageKeys {

    /** Canonical MIME -> file extension for the image types the platform accepts. */
    private static final Map<String, String> EXT_BY_TYPE = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp",
            "image/gif", "gif");

    /** Reverse map, file extension -> canonical MIME. */
    private static final Map<String, String> TYPE_BY_EXT = Map.of(
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png",
            "webp", "image/webp",
            "gif", "image/gif");

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private StorageKeys() {
    }

    /**
     * Builds a fresh {@code <prefix>/<uuid>.<ext>} key. The prefix is normalised
     * (surrounding slashes stripped) and must be non-blank; the extension is
     * derived from {@code contentType}.
     */
    public static String newKey(String keyPrefix, String contentType) {
        String prefix = normalisePrefix(keyPrefix);
        return prefix + "/" + UUID.randomUUID() + "." + extensionFor(contentType);
    }

    /**
     * @return the canonical file extension for a MIME type; for a type outside
     *         the known set, the sanitised subtype (e.g. {@code image/svg+xml}
     *         -> {@code svg}), or {@code "bin"} as a last resort. Never returns a
     *         value containing a path separator.
     */
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

    /**
     * @return the MIME type implied by a stored key's extension, or
     *         {@code application/octet-stream} when it is unknown.
     */
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
