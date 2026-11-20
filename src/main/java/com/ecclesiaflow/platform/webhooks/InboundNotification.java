package com.ecclesiaflow.platform.webhooks;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A provider's notification once its signature holds. It keeps what the notification names and a digest of its
 * body, never the body: a body can carry a payer's data, and the inbox is append-only. It only says where to look;
 * what it reports is read again from the provider.
 *
 * @param provider      the adapter's id, a lowercase word ("stripe"), also a metric tag
 * @param account       the provider account it is about; empty for the provider's own (platform) scope
 * @param objectRef     the provider object it is about; null when it names none
 * @param live          false in the provider's test mode
 * @param payloadDigest SHA-256 of the body as received, lowercase hex ({@link #digestOf})
 * @param apiVersion    the provider API version the body was written in; null when it states none
 */
public record InboundNotification(String provider, String account, String eventId, String type, boolean live,
                                  String objectRef, String payloadDigest, Instant occurredAt, String apiVersion) {

    static final int MAX_REF = 255;
    static final int MAX_TYPE = 100;
    static final int MAX_API_VERSION = 40;

    private static final Pattern PROVIDER = Pattern.compile("[a-z][a-z0-9-]{0,31}");
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

    // Messages name the field, never its value: a value comes from a provider's body.
    public InboundNotification {
        if (provider == null || !PROVIDER.matcher(provider).matches()) {
            throw new IllegalArgumentException("provider must be a lowercase word of at most 32 characters");
        }
        account = account == null ? "" : bounded("account", account, MAX_REF);
        eventId = required("eventId", eventId, MAX_REF);
        type = required("type", type, MAX_TYPE);
        objectRef = objectRef == null ? null : bounded("objectRef", objectRef, MAX_REF);
        apiVersion = apiVersion == null ? null : bounded("apiVersion", apiVersion, MAX_API_VERSION);
        if (payloadDigest == null || !DIGEST.matcher(payloadDigest).matches()) {
            throw new IllegalArgumentException("payloadDigest must be a lowercase hex SHA-256");
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    /** The digest an adapter records for the body it verified. */
    public static String digestOf(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }

    private static String required(String field, String value, int max) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return bounded(field, value, max);
    }

    private static String bounded(String field, String value, int max) {
        if (value.length() > max) {
            throw new IllegalArgumentException(field + " exceeds " + max + " characters");
        }
        return value;
    }
}
