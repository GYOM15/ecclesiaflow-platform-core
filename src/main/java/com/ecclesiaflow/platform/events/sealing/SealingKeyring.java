package com.ecclesiaflow.platform.events.sealing;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * AES-256 keys by id: the current one seals, every one opens, so a value sealed before a rotation
 * still opens while it waits in the outbox or a dead-letter queue.
 */
public final class SealingKeyring {

    public static final int KEY_LENGTH_BYTES = 32;

    private final String currentKeyId;
    private final Map<String, SecretKey> keys;

    /** {@code keys} holds the current key and the retired ones that may still be needed to open. */
    public SealingKeyring(String currentKeyId, Map<String, byte[]> keys) {
        if (currentKeyId == null || currentKeyId.isBlank()) {
            throw new IllegalArgumentException("The current sealing key id must not be blank");
        }
        if (keys == null) {
            throw new IllegalArgumentException("Sealing keys must not be null");
        }
        Map<String, SecretKey> byId = new LinkedHashMap<>();
        keys.forEach((id, material) -> byId.put(requireId(id), toKey(id, material)));
        if (!byId.containsKey(currentKeyId)) {
            throw new IllegalArgumentException("No sealing key under the current id '" + currentKeyId + "'");
        }
        this.currentKeyId = currentKeyId;
        this.keys = Map.copyOf(byId);
    }

    public String currentKeyId() {
        return currentKeyId;
    }

    Optional<SecretKey> find(String keyId) {
        return keyId == null ? Optional.empty() : Optional.ofNullable(keys.get(keyId));
    }

    SecretKey currentKey() {
        return keys.get(currentKeyId);
    }

    @Override
    public String toString() {
        return "SealingKeyring[current=" + currentKeyId + ", ids=" + keys.keySet() + "]";
    }

    private static String requireId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A sealing key id must not be blank");
        }
        return id;
    }

    private static SecretKey toKey(String id, byte[] material) {
        if (material == null || material.length != KEY_LENGTH_BYTES) {
            throw new IllegalArgumentException("Sealing key '" + id + "' must be " + KEY_LENGTH_BYTES
                    + " bytes, got " + (material == null ? 0 : material.length));
        }
        return new SecretKeySpec(material, "AES");
    }
}
