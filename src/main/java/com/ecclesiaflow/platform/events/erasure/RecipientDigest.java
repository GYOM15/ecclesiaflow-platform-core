package com.ecclesiaflow.platform.events.erasure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * How an erased person's addresses travel between modules: the SHA-256 of the address as the
 * platform writes it, in lowercase hex. The module that sends to an address can recognise its
 * digest; a reader of the event, the outbox or a dead-letter queue learns nothing it could read
 * back.
 *
 * <p>The canonical form is the one a PostgreSQL consumer rebuilds from a stored address with
 * {@code encode(sha256(convert_to(lower(btrim(address)), 'UTF8')), 'hex')}: an email trimmed and
 * lowercased, a phone number written {@code +} and its digits, which lowercasing leaves alone.
 */
public final class RecipientDigest {

    private static final Pattern PHONE_SEPARATORS = Pattern.compile("[\\s().-]");
    private static final Pattern INTERNATIONAL_NUMBER = Pattern.compile("\\+?[1-9]\\d{1,14}");

    private RecipientDigest() {
    }

    /** The digest of an email address, or empty when the value is not one. */
    public static Optional<String> ofEmail(String email) {
        if (email == null) {
            return Optional.empty();
        }
        String canonical = email.trim().toLowerCase(Locale.ROOT);
        int at = canonical.indexOf('@');
        if (at <= 0 || at == canonical.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(sha256Hex(canonical));
    }

    /** The digest of an international phone number, or empty when the value is not one. */
    public static Optional<String> ofPhone(String phone) {
        if (phone == null) {
            return Optional.empty();
        }
        String compact = PHONE_SEPARATORS.matcher(phone.trim()).replaceAll("");
        if (!INTERNATIONAL_NUMBER.matcher(compact).matches()) {
            return Optional.empty();
        }
        return Optional.of(sha256Hex(compact.startsWith("+") ? compact : "+" + compact));
    }

    /** Whether {@code value} has the shape of a digest, so a consumer can refuse anything else. */
    public static boolean isDigest(String value) {
        return value != null && value.length() == 64 && value.chars().allMatch(RecipientDigest::isLowerHex);
    }

    private static boolean isLowerHex(int c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
    }

    private static String sha256Hex(String canonical) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every Java runtime", e);
        }
    }
}
