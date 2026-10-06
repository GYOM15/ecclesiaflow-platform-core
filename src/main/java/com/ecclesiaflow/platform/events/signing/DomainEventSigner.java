package com.ecclesiaflow.platform.events.signing;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

/**
 * HMAC-SHA256 signature carried in the {@value #SIGNATURE_HEADER} header of cross-module RabbitMQ
 * domain events. The body is never modified, so consumers keep deserializing the original bytes.
 *
 * <p>Signed material: {@code exchange ‖ 0x00 ‖ routingKey ‖ 0x00 ‖ signedAt ‖ 0x00 ‖ body}. Binding
 * the destination and the instant stops a signed body from being replayed under another exchange or
 * routing key, or replayed forever. The NUL separator is injective only because none of these fields
 * can contain U+0000; a field that could (e.g. a user-derived routing key) would need length-prefixing.</p>
 *
 * <p>A blank secret disables signing: check {@link #isEnabled()} and skip the header rather than sign
 * with an empty key.</p>
 */
public class DomainEventSigner {

    /** The {@code x-ef-} prefix keeps it clear of AMQP/Spring reserved headers. */
    public static final String SIGNATURE_HEADER = "x-ef-signature";

    /** Epoch milliseconds, signed with the body so the consumer can refuse a replay. */
    public static final String SIGNED_AT_HEADER = "x-ef-signed-at";

    /**
     * Names the canonical form the signature covers, so that form can change during a rolling deploy:
     * consumers learn a new version before publishers emit it. Absent means version 1.
     */
    public static final String SIGNATURE_VERSION_HEADER = "x-ef-signature-version";

    /** The canonical form {@link #sign} produces and {@link #matches} checks. */
    public static final String SIGNATURE_VERSION = "1";

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final byte SEPARATOR = 0x00;

    private final byte[] secret;

    /** A {@code null} or blank secret disables signing. */
    public DomainEventSigner(String hmacSecret) {
        this.secret = (hmacSecret == null || hmacSecret.isBlank())
                ? null
                : hmacSecret.getBytes(StandardCharsets.UTF_8);
    }

    public boolean isEnabled() {
        return secret != null;
    }

    /**
     * Returns the Base64 value for {@value #SIGNATURE_HEADER}; {@code signedAt} must travel in
     * {@value #SIGNED_AT_HEADER}. Throws {@link IllegalStateException} when signing is disabled.
     */
    public String sign(String exchange, String routingKey, long signedAt, byte[] body) {
        if (!isEnabled()) {
            throw new IllegalStateException("HMAC secret is not configured; signing is disabled");
        }
        if (body == null) {
            throw new IllegalArgumentException("body must not be null");
        }
        return Base64.getEncoder().encodeToString(
                computeMac(secret, canonical(exchange, routingKey, Long.toString(signedAt), body)));
    }

    /**
     * Constant-time check; a missing or malformed candidate yields {@code false}. {@code signedAt} is the
     * raw header string because re-rendering a parsed number (leading zeros, a plus sign) would change the
     * signed bytes.
     */
    public boolean matches(String exchange, String routingKey, String signedAt,
                           byte[] body, String candidate) {
        if (!isEnabled() || body == null || candidate == null || candidate.isBlank()) {
            return false;
        }
        byte[] candidateBytes;
        try {
            candidateBytes = Base64.getDecoder().decode(candidate);
        } catch (IllegalArgumentException e) {
            return false;
        }
        byte[] expected = computeMac(secret, canonical(exchange, routingKey, signedAt, body));
        return constantTimeEquals(expected, candidateBytes);
    }

    /** Package-private so tests can pin the exact signed bytes. */
    static byte[] canonical(String exchange, String routingKey, String signedAt, byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(body.length + 64);
        writeField(out, exchange);
        writeField(out, routingKey);
        writeField(out, signedAt);
        out.write(body, 0, body.length);
        return out.toByteArray();
    }

    private static void writeField(ByteArrayOutputStream out, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        out.write(bytes, 0, bytes.length);
        out.write(SEPARATOR);
    }

    private static byte[] computeMac(byte[] secret, byte[] material) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(material);
        } catch (GeneralSecurityException e) {
            // HmacSHA256 is mandated by every JRE; an absence is unrecoverable.
            throw new IllegalStateException("HMAC-SHA256 unavailable in this JVM", e);
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }
}
