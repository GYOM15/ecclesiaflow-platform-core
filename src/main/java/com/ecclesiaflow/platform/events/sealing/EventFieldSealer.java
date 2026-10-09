package com.ecclesiaflow.platform.events.sealing;

import com.ecclesiaflow.platform.events.sealing.SealedFieldUnreadableException.Reason;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/**
 * Seals a secret carried by a domain event, so it reads as ciphertext on the broker, in the outbox
 * and in a dead-letter queue. AES-256-GCM with a random 12-byte nonce; a sealed value is the nonce
 * followed by the ciphertext and its 16-byte tag. The event id is the associated data, so a value
 * copied into another event does not open.
 */
public class EventFieldSealer {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int MIN_SEALED_LENGTH = NONCE_LENGTH_BYTES + TAG_LENGTH_BITS / 8;

    private final SealingKeyring keyring;
    private final SecureRandom random = new SecureRandom();

    public EventFieldSealer(SealingKeyring keyring) {
        this.keyring = keyring;
    }

    /** Travels beside every value {@link #seal} returns, so the consumer picks the key that opens it. */
    public String keyId() {
        return keyring.currentKeyId();
    }

    public byte[] seal(String plaintext, String eventId) {
        if (plaintext == null) {
            throw new IllegalArgumentException("Nothing to seal");
        }
        if (isBlank(eventId)) {
            throw new IllegalArgumentException("A sealed value is bound to an event id, which is blank");
        }
        byte[] nonce = new byte[NONCE_LENGTH_BYTES];
        random.nextBytes(nonce);
        byte[] ciphertext = run(Cipher.ENCRYPT_MODE, keyring.currentKey(), nonce, eventId,
                plaintext.getBytes(StandardCharsets.UTF_8), 0);
        byte[] sealed = new byte[NONCE_LENGTH_BYTES + ciphertext.length];
        System.arraycopy(nonce, 0, sealed, 0, NONCE_LENGTH_BYTES);
        System.arraycopy(ciphertext, 0, sealed, NONCE_LENGTH_BYTES, ciphertext.length);
        return sealed;
    }

    /** Throws {@link SealedFieldUnreadableException} for anything that does not open. */
    public String open(String keyId, byte[] sealed, String eventId) {
        SecretKey key = keyring.find(keyId)
                .orElseThrow(() -> new SealedFieldUnreadableException(Reason.UNKNOWN_KEY));
        if (sealed == null || sealed.length < MIN_SEALED_LENGTH || isBlank(eventId)) {
            throw new SealedFieldUnreadableException(Reason.MALFORMED);
        }
        byte[] plaintext = run(Cipher.DECRYPT_MODE, key, sealed, eventId, sealed, NONCE_LENGTH_BYTES);
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    /** The nonce is the first 12 bytes of {@code nonceSource}; the input starts at {@code offset}. */
    private static byte[] run(int mode, SecretKey key, byte[] nonceSource, String eventId,
                              byte[] input, int offset) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(mode, key, new GCMParameterSpec(TAG_LENGTH_BITS, nonceSource, 0, NONCE_LENGTH_BYTES));
            cipher.updateAAD(eventId.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(input, offset, input.length - offset);
        } catch (AEADBadTagException e) {
            throw new SealedFieldUnreadableException(Reason.NOT_AUTHENTIC);
        } catch (GeneralSecurityException e) {
            // Every Java runtime ships AES-GCM, and the keyring only holds 32-byte keys.
            throw new IllegalStateException("AES-GCM failed on a well-formed key", e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
