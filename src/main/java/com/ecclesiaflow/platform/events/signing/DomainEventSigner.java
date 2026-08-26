package com.ecclesiaflow.platform.events.signing;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.util.Base64;

/**
 * Computes the HMAC-SHA256 signature carried in the {@value #SIGNATURE_HEADER}
 * header of cross-module RabbitMQ domain events (security finding C07 — forged
 * domain events → account-takeover / phishing).
 *
 * <p>Framework-light: pure JDK crypto, no Spring/AMQP types. The signature is
 * computed over the raw (already-serialized, typically protobuf) message body —
 * the wire body is never modified, so existing consumers keep deserializing
 * untouched bytes. Body-binding is what defeats C07: a forger who cannot produce
 * a valid HMAC over the payload cannot mint a legitimate setup-token / admission
 * event.</p>
 *
 * <p>The signed material is the body bytes alone, deliberately independent of
 * the routing key. On the publish side the routing key is supplied to
 * {@code RabbitTemplate.convertAndSend(routingKey, ...)} separately and is
 * <em>not</em> present on the {@code MessageProperties} a
 * {@link org.springframework.amqp.core.MessagePostProcessor} sees, so binding it
 * would make the publish and consume computations asymmetric. The
 * {@link DomainEventSigner} and {@link DomainEventVerifier} share
 * {@link #computeMac(byte[], byte[])} so the two can never drift.</p>
 *
 * <p>A blank secret means signing is disabled (migration escape hatch); callers
 * should consult {@link #isEnabled()} and skip stamping the header rather than
 * sign with an empty key.</p>
 */
public class DomainEventSigner {

    /**
     * Stable AMQP message header that carries the Base64 HMAC-SHA256 signature.
     * Lowercase, {@code x-ef-} prefixed to match EcclesiaFlow's custom-header
     * convention and to stay clear of AMQP/Spring reserved headers.
     */
    public static final String SIGNATURE_HEADER = "x-ef-signature";

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] secret;

    /**
     * @param hmacSecret the shared secret; {@code null} or blank disables signing
     *                   ({@link #isEnabled()} returns {@code false}).
     */
    public DomainEventSigner(String hmacSecret) {
        this.secret = (hmacSecret == null || hmacSecret.isBlank())
                ? null
                : hmacSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Whether a usable secret is configured. {@code false} disables signing. */
    public boolean isEnabled() {
        return secret != null;
    }

    /**
     * Computes the Base64-encoded HMAC-SHA256 signature for the given message
     * body.
     *
     * @param body the raw serialized message body (the protobuf wire bytes)
     * @return the Base64 signature to place in {@value #SIGNATURE_HEADER}
     * @throws IllegalStateException    if signing is disabled (blank secret)
     * @throws IllegalArgumentException if {@code body} is null
     */
    public String sign(byte[] body) {
        if (!isEnabled()) {
            throw new IllegalStateException("HMAC secret is not configured; signing is disabled");
        }
        if (body == null) {
            throw new IllegalArgumentException("body must not be null");
        }
        return Base64.getEncoder().encodeToString(computeMac(secret, body));
    }

    /**
     * Recomputes the signature and compares it, in constant time, to the
     * candidate carried on the wire.
     *
     * @param body      the raw message body bytes
     * @param candidate the Base64 signature from {@value #SIGNATURE_HEADER};
     *                  {@code null} / blank / malformed all yield {@code false}
     * @return {@code true} iff the candidate matches and signing is enabled
     */
    public boolean matches(byte[] body, String candidate) {
        if (!isEnabled() || body == null || candidate == null || candidate.isBlank()) {
            return false;
        }
        byte[] candidateBytes;
        try {
            candidateBytes = Base64.getDecoder().decode(candidate);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return constantTimeEquals(computeMac(secret, body), candidateBytes);
    }

    private static byte[] computeMac(byte[] secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            // HmacSHA256 is mandated by every JRE; an absence is unrecoverable.
            throw new IllegalStateException("HMAC-SHA256 unavailable in this JVM", e);
        }
    }

    /**
     * Length-aware constant-time comparison — never short-circuits on the first
     * differing byte, so it leaks neither match length nor position to a timing
     * attacker.
     */
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
