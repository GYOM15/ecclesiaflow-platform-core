package com.ecclesiaflow.platform.events.signing;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

/**
 * Computes the HMAC-SHA256 signature carried in the {@value #SIGNATURE_HEADER}
 * header of cross-module RabbitMQ domain events (security finding C07 — forged
 * domain events → account-takeover / phishing).
 *
 * <p>Framework-light: pure JDK crypto, no Spring/AMQP types. The wire body is
 * never modified, so existing consumers keep deserializing untouched bytes.</p>
 *
 * <h2>What is signed, and why it is not just the body</h2>
 *
 * <p>The signed material is the canonical byte string</p>
 *
 * <pre>exchange ‖ 0x00 ‖ routingKey ‖ 0x00 ‖ signedAt ‖ 0x00 ‖ body</pre>
 *
 * <p>An earlier version signed the body alone. That bound a signature to the
 * payload but to <em>nothing else</em>: anyone able to place a message on the
 * broker could take a legitimately signed event and re-publish those same bytes
 * under a different routing key or exchange, and the signature still verified.
 * With a single serializer shared across the fleet, a body that parses as one
 * event type generally parses as its siblings, so a « member profile changed »
 * could be replayed as a « member removed ». The same hole let an old event be
 * replayed forever. Binding the destination and the signing instant closes
 * both: a signature is now valid for one exchange, one routing key and one
 * moment (security finding F054).</p>
 *
 * <p>The three prefix fields are UTF-8 text, separated by NUL — a byte that
 * standard UTF-8 produces only for U+0000 itself, and never as part of any other
 * character's encoding. (Only Java's <em>modified</em> UTF-8 encodes U+0000 as
 * two bytes; {@code String.getBytes(UTF_8)} emits a single {@code 0x00}.) The
 * three fields are code-defined AMQP names and a decimal instant, none of which
 * contains U+0000, so no choice of exchange or routing key can be made to look
 * like another combination.</p>
 *
 * <p>Stated that precisely because the earlier wording — « a byte UTF-8 can
 * never produce » — was simply wrong, and the property it claimed is
 * conditional on the inputs rather than guaranteed by the encoding. A field that
 * could ever carry a NUL, such as a tenant- or user-derived routing key, would
 * break injectivity and would need length-prefixing instead of a separator.</p>
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

    /**
     * Header carrying the signing instant as epoch milliseconds, decimal, no
     * separators. It is part of the signed material, so a forger cannot move it:
     * changing it invalidates the signature. It exists so the consumer can
     * refuse a replay of a genuinely signed event (see
     * {@link DomainEventVerifier}).
     */
    public static final String SIGNED_AT_HEADER = "x-ef-signed-at";

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final byte SEPARATOR = 0x00;

    private final byte[] secret;

    /**
     * @param hmacSecret the shared secret; {@code null} or blank disables signing
     *                   ({@link #isEnabled()} returns {@code false}).
     */
    public DomainEventSigner(String hmacSecret) {
        this.secret = (hmacSecret == null || hmacSecret.isBlank())
                ? null
                : hmacSecret.getBytes(StandardCharsets.UTF_8);
    }

    /** Whether a usable secret is configured. {@code false} disables signing. */
    public boolean isEnabled() {
        return secret != null;
    }

    /**
     * Computes the Base64-encoded HMAC-SHA256 signature binding a message body
     * to the destination it is published to and the instant it is signed.
     *
     * @param exchange   the exchange the message is published to ({@code null} → {@code ""})
     * @param routingKey the routing key it is published with ({@code null} → {@code ""})
     * @param signedAt   the signing instant, epoch milliseconds; goes on the wire
     *                   in {@value #SIGNED_AT_HEADER} and must be passed back to
     *                   {@link #matches} verbatim
     * @param body       the raw serialized message body (the protobuf wire bytes)
     * @return the Base64 signature to place in {@value #SIGNATURE_HEADER}
     * @throws IllegalStateException    if signing is disabled (blank secret)
     * @throws IllegalArgumentException if {@code body} is null
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
     * Recomputes the signature over the same canonical material and compares it,
     * in constant time, to the candidate carried on the wire.
     *
     * <p>{@code signedAt} is taken as the raw header <em>string</em>, not a
     * parsed number: the bytes that were signed are the bytes that travelled,
     * and re-rendering a parsed value could differ (leading zeros, a plus sign)
     * and silently fail every verification.</p>
     *
     * @param exchange   the exchange the message was received on
     * @param routingKey the routing key it was received with
     * @param signedAt   the {@value #SIGNED_AT_HEADER} value exactly as received
     * @param body       the raw message body bytes
     * @param candidate  the Base64 signature from {@value #SIGNATURE_HEADER};
     *                   {@code null} / blank / malformed all yield {@code false}
     * @return {@code true} iff the candidate matches and signing is enabled
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

    /**
     * {@code exchange ‖ 0x00 ‖ routingKey ‖ 0x00 ‖ signedAt ‖ 0x00 ‖ body}.
     * Package-private so the verifier's tests can pin the exact bytes.
     */
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
