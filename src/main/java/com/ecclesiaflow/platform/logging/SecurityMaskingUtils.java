package com.ecclesiaflow.platform.logging;

import java.util.Arrays;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Utility for masking sensitive data before it reaches a log line. Shared by
 * every EcclesiaFlow backend module so log shape stays consistent across the
 * platform.
 *
 * <p>Helpers cover the typical sources of leakage: emails ({@link #maskEmail}),
 * JWTs and tokens ({@link #maskAny}, {@link #maskUrlQueryParam}), database /
 * service identifiers ({@link #maskId}), and exception messages produced by
 * infrastructure layers ({@link #sanitizeInfra}, {@link #rootMessage}).
 */
public final class SecurityMaskingUtils {

    private static final String MASK = "****";
    private static final String UNKNOWN = "[UNKNOWN]";
    private static final String INVALID = "[INVALID_FORMAT]";
    private static final String URL_MASKING_ERROR = "[URL_MASKING_ERROR]";
    private static final String REDACTED = "[REDACTED]";

    private static final Pattern EMAIL_LIKE =
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private static final Pattern JWT_LIKE =
            Pattern.compile("^[A-Za-z0-9\\-_]+\\.[A-Za-z0-9\\-_]+\\.[A-Za-z0-9\\-_]+$");

    private SecurityMaskingUtils() {
    }

    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) return UNKNOWN;

        int atIndex = email.indexOf('@');
        if (atIndex <= 0) return INVALID;

        String local = email.substring(0, atIndex);
        String domain = email.substring(atIndex);

        if (local.length() <= 2) {
            return local.charAt(0) + MASK + domain;
        }
        return local.substring(0, 2) + MASK + domain;
    }

    public static String maskUrlQueryParam(String url, String paramName) {
        if (url == null || url.isBlank()) return UNKNOWN;
        if (paramName == null || paramName.isBlank()) return "[URL]";

        try {
            int queryIndex = url.indexOf('?');
            if (queryIndex < 0) return "[URL]";

            String base = url.substring(0, queryIndex);
            String query = url.substring(queryIndex + 1);

            String[] params = query.split("&");
            StringBuilder masked = new StringBuilder();
            for (String param : params) {
                if (!masked.isEmpty()) masked.append("&");
                int eq = param.indexOf('=');
                if (eq < 0) {
                    masked.append(param);
                    continue;
                }
                String key = param.substring(0, eq);
                masked.append(key).append("=");
                masked.append(key.equals(paramName) ? MASK : REDACTED);
            }
            return base + "?" + masked;
        } catch (Exception e) {
            return URL_MASKING_ERROR;
        }
    }

    public static String maskId(Object id) {
        if (id == null) return UNKNOWN;
        String s = String.valueOf(id);
        if (s.isBlank()) return UNKNOWN;
        if (looksLikeUuid(s)) {
            return s.substring(0, 8) + "********";
        }
        if (s.length() <= 8) return "********";
        return s.substring(0, 8) + "********";
    }

    public static String maskArgs(Object[] args) {
        if (args == null) return "[]";
        String[] masked = new String[args.length];
        for (int i = 0; i < args.length; i++) {
            masked[i] = maskAny(args[i]);
        }
        return Arrays.toString(masked);
    }

    public static String maskAny(Object value) {
        if (value == null) return UNKNOWN;
        String raw = String.valueOf(value);
        if (raw.isBlank()) return UNKNOWN;

        if (EMAIL_LIKE.matcher(raw).matches()) {
            return maskEmail(raw);
        }
        if (JWT_LIKE.matcher(raw).matches()) {
            return REDACTED;
        }
        if (raw.startsWith("http://") || raw.startsWith("https://")) {
            return raw.contains("token=") ? maskUrlQueryParam(raw, "token") : "[URL]";
        }
        if (raw.regionMatches(true, 0, "bearer ", 0, 7)) {
            return "Bearer " + MASK;
        }
        return abbreviate(raw, 120);
    }

    public static String rootMessage(Throwable t) {
        if (t == null) return "[NO_ERROR]";
        Throwable cur = t;
        while (cur.getCause() != null) cur = cur.getCause();
        String msg = cur.getMessage();
        return (msg != null && !msg.isBlank())
                ? sanitizeInfra(msg)
                : cur.getClass().getSimpleName();
    }

    public static String sanitizeInfra(String msg) {
        if (msg == null || msg.isBlank()) return msg;
        String s = msg;
        s = s.replaceAll("https?://[^\\s]+", "[URL]");
        s = s.replaceAll("[a-zA-Z0-9._-]+:\\d{2,5}", "[HOST:PORT]");
        s = s.replaceAll("(?<!@)[a-zA-Z0-9._-]+\\.[a-zA-Z]{2,}(?![a-zA-Z0-9._-])", "[HOST]");
        return s;
    }

    public static String abbreviate(String s, int max) {
        if (s == null) return UNKNOWN;
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }

    private static boolean looksLikeUuid(String s) {
        try {
            UUID.fromString(s);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
