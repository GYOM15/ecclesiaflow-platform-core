package com.ecclesiaflow.platform.events.retry;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * HMAC-SHA256 over a {@link RetryHop}, the event's signature headers and its body, carried in
 * {@value RetryHop#STAMP_HEADER}. It is what lets a redelivery claim to come back from one of its
 * consumer's retry stages: the broker's {@code x-death} header cannot, since RabbitMQ 3.13.3+ takes it
 * as given from any publisher (docs/delayed-retries.md).
 *
 * <p>Keyed by a key derived from the events secret, never by the secret itself, so a stamp cannot pass
 * for an event signature over the same bytes, nor the reverse. Fields are length-prefixed, so no
 * value, whatever bytes it holds, can shift into its neighbour.</p>
 *
 * <p>A blank secret disables stamping, as it disables signing.</p>
 */
public class DelayedRetryStamp {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final byte[] KEY_LABEL = "ecclesiaflow.events.retry-stamp.v1".getBytes(StandardCharsets.UTF_8);
    private static final int ABSENT = -1;

    private final byte[] key;

    /** A {@code null} or blank secret disables stamping. */
    public DelayedRetryStamp(String hmacSecret) {
        this.key = (hmacSecret == null || hmacSecret.isBlank())
                ? null
                : mac(hmacSecret.getBytes(StandardCharsets.UTF_8), KEY_LABEL);
    }

    public boolean isEnabled() {
        return key != null;
    }

    /**
     * {@code signedAt} and {@code signature} are the raw event headers, {@code null} when absent. Throws
     * {@link IllegalStateException} when stamping is disabled.
     */
    public String sign(RetryHop hop, String signedAt, String signature, byte[] body) {
        if (!isEnabled()) {
            throw new IllegalStateException("HMAC secret is not configured; retry stamping is disabled");
        }
        if (hop == null || body == null) {
            throw new IllegalArgumentException("A stamp covers a hop and a body");
        }
        return Base64.getEncoder().encodeToString(mac(key, canonical(hop, signedAt, signature, body)));
    }

    /** Constant-time; a missing or malformed candidate never matches. */
    public boolean matches(RetryHop hop, String signedAt, String signature, byte[] body, String candidate) {
        if (!isEnabled() || hop == null || body == null || candidate == null || candidate.isBlank()) {
            return false;
        }
        byte[] candidateBytes;
        try {
            candidateBytes = Base64.getDecoder().decode(candidate);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(mac(key, canonical(hop, signedAt, signature, body)), candidateBytes);
    }

    /** Package-private so tests can pin the exact stamped bytes. */
    static byte[] canonical(RetryHop hop, String signedAt, String signature, byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(body.length + 256);
        hop.headers().values().forEach(value -> writeField(out, value));
        writeField(out, signedAt);
        writeField(out, signature);
        writeField(out, body);
        return out.toByteArray();
    }

    private static void writeField(ByteArrayOutputStream out, String value) {
        if (value == null) {
            out.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(ABSENT).array());
            return;
        }
        writeField(out, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeField(ByteArrayOutputStream out, byte[] value) {
        out.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        out.writeBytes(value);
    }

    private static byte[] mac(byte[] macKey, byte[] material) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(macKey, HMAC_ALGORITHM));
            return mac.doFinal(material);
        } catch (GeneralSecurityException e) {
            // HmacSHA256 is mandated by every JRE; an absence is unrecoverable.
            throw new IllegalStateException("HMAC-SHA256 unavailable in this JVM", e);
        }
    }
}
