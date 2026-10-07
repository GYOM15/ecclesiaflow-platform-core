package com.ecclesiaflow.platform.logging;

import java.util.Arrays;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SecurityMaskingUtils {

    private static final String MASK = "****";
    private static final String EMAIL_MASK = "***";
    private static final String UNKNOWN = "[UNKNOWN]";
    private static final String INVALID = "[INVALID_FORMAT]";
    private static final String URL_MASKING_ERROR = "[URL_MASKING_ERROR]";
    private static final String REDACTED = "[REDACTED]";
    private static final int PHONE_VISIBLE_DIGITS = 2;

    private static final Pattern EMAIL_LIKE =
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private static final Pattern TLD = Pattern.compile("[A-Za-z]{2,63}");

    private static final Pattern JWT_LIKE =
            Pattern.compile("^[A-Za-z0-9\\-_]+\\.[A-Za-z0-9\\-_]+\\.[A-Za-z0-9\\-_]+$");

    private static final Pattern PHONE_LIKE =
            Pattern.compile("^\\+?[0-9 ()\\-.]{6,20}$");

    // Lookbehinds anchor matches at token starts: linear backtracking, and no match starting mid-word.
    private static final Pattern BEARER_IN_TEXT =
            Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern JWT_IN_TEXT =
            Pattern.compile("\\beyJ[A-Za-z0-9_-]*(?:\\.[A-Za-z0-9_-]*)+");
    private static final Pattern URI_IN_TEXT =
            Pattern.compile("\\b[a-zA-Z][a-zA-Z0-9+.-]*://[^\\s\"'<>]+");
    private static final Pattern EMAIL_IN_TEXT =
            Pattern.compile("(?<![^\\s@()<>])[^\\s@()<>]+@[^\\s@()<>]+");
    private static final Pattern E164_IN_TEXT =
            Pattern.compile("(?<![\\w+])\\+\\d{8,15}(?!\\d)");
    // InetSocketAddress.toString(): "redis/<unresolved>:6379", "keycloak/172.18.0.3:8080".
    private static final Pattern SOCKET_ADDRESS_IN_TEXT =
            Pattern.compile("(?<![a-zA-Z0-9._-])[a-zA-Z0-9._-]*/(?:<unresolved>|[0-9a-fA-F.:]+):\\d{2,5}(?!\\d)");
    private static final Pattern HOST_PORT_IN_TEXT =
            Pattern.compile("[a-zA-Z0-9._-]+:\\d{2,5}");
    private static final Pattern HOST_IN_TEXT =
            Pattern.compile("(?<![a-zA-Z0-9._-])[a-zA-Z0-9._-]+\\.[a-zA-Z]{2,}(?![a-zA-Z0-9._-])");

    private SecurityMaskingUtils() {
    }

    /** {@code alice@church.com} → {@code a***e@***.com}. */
    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) return UNKNOWN;

        int atIndex = email.indexOf('@');
        if (atIndex <= 0) return INVALID;

        return maskLocalPart(email.substring(0, atIndex)) + "@" + maskDomain(email.substring(atIndex + 1));
    }

    // With one or two characters, first-and-last would give the whole local part back.
    private static String maskLocalPart(String local) {
        String first = local.substring(0, local.offsetByCodePoints(0, 1));
        if (local.codePointCount(0, local.length()) <= 2) {
            return first + EMAIL_MASK;
        }
        return first + EMAIL_MASK + local.substring(local.offsetByCodePoints(local.length(), -1));
    }

    // A family or parish domain identifies a person as surely as the local part.
    private static String maskDomain(String domain) {
        int lastDot = domain.lastIndexOf('.');
        String tld = lastDot < 0 ? "" : domain.substring(lastDot + 1);
        return TLD.matcher(tld).matches() ? EMAIL_MASK + "." + tld : EMAIL_MASK;
    }

    /** Keeps a leading {@code +} and the last two digits: {@code +33612345678} → {@code +****78}. */
    public static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) return UNKNOWN;

        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return INVALID;

        String prefix = phone.strip().startsWith("+") ? "+" : "";
        if (digits.length() <= PHONE_VISIBLE_DIGITS) {
            return prefix + MASK;
        }
        return prefix + MASK + digits.substring(digits.length() - PHONE_VISIBLE_DIGITS);
    }

    /** Keeps only the length of a free-text body (chat message, prayer request...). */
    public static String maskBody(String body) {
        if (body == null) return UNKNOWN;
        return "[BODY length=" + body.length() + "]";
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

    public static String maskConfirmationLink(String link) {
        return maskUrlQueryParam(link, "token");
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

    /**
     * Deny by default: unrecognised text is redacted, since a password, a name or an address looks like any
     * other text. Only numbers, booleans and enum constants print as is; other objects show their type.
     */
    public static String maskAny(Object value) {
        if (value == null) return UNKNOWN;
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        if (value instanceof Enum<?> constant) return constant.name();
        if (value instanceof UUID) return maskId(value);
        if (!(value instanceof CharSequence)) return typeLabel(value);

        String raw = value.toString();
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
        if (looksLikeUuid(raw)) {
            return maskId(raw);
        }
        if (PHONE_LIKE.matcher(raw).matches()) {
            return maskPhone(raw);
        }
        return REDACTED;
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

    // Bounded before the patterns run: the input comes from drivers and remote
    // services, and an attacker-lengthened message must not cost minutes of regex.
    private static final int MAX_INFRA_MESSAGE = 512;

    /**
     * Strips what an exception message may carry from the infrastructure or about
     * a person: bearer tokens and JWTs, URIs of any scheme, emails, E.164 phone
     * numbers, socket addresses, {@code host:port} and bare host names.
     */
    public static String sanitizeInfra(String msg) {
        if (msg == null || msg.isBlank()) return msg;
        String s = abbreviate(msg, MAX_INFRA_MESSAGE);
        s = BEARER_IN_TEXT.matcher(s).replaceAll("Bearer " + MASK);
        s = JWT_IN_TEXT.matcher(s).replaceAll(Matcher.quoteReplacement(REDACTED));
        s = URI_IN_TEXT.matcher(s).replaceAll(Matcher.quoteReplacement("[URL]"));
        s = EMAIL_IN_TEXT.matcher(s).replaceAll(m -> Matcher.quoteReplacement(maskEmailInText(m.group())));
        s = E164_IN_TEXT.matcher(s).replaceAll(m -> Matcher.quoteReplacement(maskPhone(m.group())));
        s = SOCKET_ADDRESS_IN_TEXT.matcher(s).replaceAll(Matcher.quoteReplacement("[HOST:PORT]"));
        s = HOST_PORT_IN_TEXT.matcher(s).replaceAll(Matcher.quoteReplacement("[HOST:PORT]"));
        s = HOST_IN_TEXT.matcher(s).replaceAll(Matcher.quoteReplacement("[HOST]"));
        return s;
    }

    /**
     * Escapes what would let an untrusted value forge or disguise a log line:
     * {@code "a\r\nb"} → {@code "a\\r\\nb"}, other controls, line and paragraph
     * separators and invisible format characters → {@code \\uXXXX}. A backslash is
     * doubled, so an escape in the log always means the value held that control.
     */
    public static String escapeControlChars(String value) {
        if (value == null || value.codePoints().noneMatch(SecurityMaskingUtils::needsEscape)) return value;

        StringBuilder escaped = new StringBuilder(value.length() + 16);
        value.codePoints().forEach(cp -> appendEscaped(escaped, cp));
        return escaped.toString();
    }

    private static void appendEscaped(StringBuilder out, int cp) {
        if (!needsEscape(cp)) {
            out.appendCodePoint(cp);
            return;
        }
        switch (cp) {
            case '\\' -> out.append("\\\\");
            case '\r' -> out.append("\\r");
            case '\n' -> out.append("\\n");
            case '\t' -> out.append("\\t");
            default -> {
                for (char unit : Character.toChars(cp)) out.append(String.format("\\u%04X", (int) unit));
            }
        }
    }

    private static boolean needsEscape(int cp) {
        return cp == '\\' || isLogControl(cp);
    }

    // Line breaks start a forged line; the other controls and the format
    // characters (bidi overrides, tags) rewrite how the real one reads.
    private static boolean isLogControl(int cp) {
        int type = Character.getType(cp);
        return type == Character.CONTROL || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR;
    }

    public static String abbreviate(String s, int max) {
        if (s == null) return UNKNOWN;
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }

    // The text match can take in the quotes or punctuation around an address;
    // they stay readable, and out of the TLD the mask keeps.
    private static String maskEmailInText(String match) {
        int at = match.indexOf('@');
        int start = 0;
        while (start < at && isPunctuation(match.charAt(start))) start++;
        int end = match.length();
        while (end > at + 1 && isPunctuation(match.charAt(end - 1))) end--;
        return match.substring(0, start) + maskEmail(match.substring(start, end)) + match.substring(end);
    }

    private static boolean isPunctuation(char c) {
        return !Character.isLetterOrDigit(c) && !Character.isSurrogate(c);
    }

    private static String typeLabel(Object value) {
        String name = value.getClass().getSimpleName();
        return "[" + (name.isEmpty() ? "Object" : name) + "]";
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
