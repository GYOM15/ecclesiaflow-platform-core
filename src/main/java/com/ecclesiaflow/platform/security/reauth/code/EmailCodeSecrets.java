package com.ecclesiaflow.platform.security.reauth.code;

import com.ecclesiaflow.platform.idempotency.IdempotencyFingerprint;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The code and the keyed digests stored in its place: a copy of the database gives no code back, a code's digest
 * is worth nothing for another challenge, and no digest of one purpose stands for another's. A plain hash would
 * fall to a million tries.
 */
public final class EmailCodeSecrets {

    public static final int KEY_BYTES = 32;
    private static final int CODE_SPACE = 1_000_000;
    private static final int DIGEST_BYTES = 32;
    private static final String ALGORITHM = "HmacSHA256";
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

    private final SecretKeySpec key;
    private final SecureRandom random;

    public EmailCodeSecrets(byte[] key, SecureRandom random) {
        Objects.requireNonNull(key, "key");
        if (key.length < KEY_BYTES) {
            throw new IllegalArgumentException("The e-mail code key needs " + KEY_BYTES + " bytes at least");
        }
        this.key = new SecretKeySpec(key, ALGORITHM);
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * The key from its base64 text, standard or URL-safe.
     *
     * @throws IllegalStateException when it is blank, not base64 or under 32 bytes, without quoting it
     */
    public static byte[] decodeKey(String value, String setting) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(setting + " is required: 32 random bytes or more, base64");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(value.strip().replace('-', '+').replace('_', '/'));
            if (decoded.length >= KEY_BYTES) {
                return decoded;
            }
        } catch (IllegalArgumentException malformed) {
            // Refused below, without the value.
        }
        throw new IllegalStateException(setting + " must be 32 random bytes or more, base64");
    }

    /** Six digits, uniformly drawn: the leading zeros are part of the code. */
    public String newCode() {
        return String.format("%06d", random.nextInt(CODE_SPACE));
    }

    /** Bound to the challenge, so a code's digest is worth nothing for another one. */
    public byte[] codeMac(UUID challengeId, String code) {
        byte[] codeBytes = code.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer input = ByteBuffer.allocate(2 * Long.BYTES + codeBytes.length)
                .putLong(challengeId.getMostSignificantBits())
                .putLong(challengeId.getLeastSignificantBits())
                .put(codeBytes);
        return mac("code", input.array());
    }

    public String codeDigest(UUID challengeId, String code) {
        return HexFormat.of().formatHex(codeMac(challengeId, code));
    }

    /** Constant time, and as slow for a missing or erased digest as for a wrong code. */
    public boolean codeMatches(byte[] presented, String storedDigest) {
        boolean stored = storedDigest != null && DIGEST.matcher(storedDigest).matches();
        byte[] expected = stored ? HexFormat.of().parseHex(storedDigest) : new byte[DIGEST_BYTES];
        return MessageDigest.isEqual(presented, expected) && stored;
    }

    /** Of the parts, written as {@link IdempotencyFingerprint} writes them: length first, unstable parts refused. */
    public String bindingDigest(Object... parts) {
        return HexFormat.of().formatHex(mac("binding", IdempotencyFingerprint.canonicalForm(parts)));
    }

    public boolean sameBinding(String stored, String presented) {
        return MessageDigest.isEqual(stored.getBytes(StandardCharsets.US_ASCII),
                presented.getBytes(StandardCharsets.US_ASCII));
    }

    // The purpose and a zero byte first: two purposes never digest the same input to the same value.
    private byte[] mac(String purpose, byte[] value) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            mac.update(purpose.getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) 0);
            return mac.doFinal(value);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is required of every JVM", e);
        }
    }

    @Override
    public String toString() {
        return "EmailCodeSecrets";
    }
}
