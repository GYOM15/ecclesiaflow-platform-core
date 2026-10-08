package com.ecclesiaflow.platform.events.sealing;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The keys of one sealed field. Not bound to a fixed prefix: the module binds it under the prefix of
 * the field it seals ({@code @ConfigurationProperties} on a {@code @Bean} method), so each field
 * rotates its key on its own.
 */
public class SealingKeyProperties {

    /** Sent beside every sealed value; a new key takes a new id. */
    private String keyId = "";

    /** Base64 of 32 random bytes ({@code openssl rand -base64 32}). No default: blank leaves sealing unconfigured. */
    private String key = "";

    /** Id to Base64 key, open-only: keep a retired key until no value it sealed can still be delivered. */
    private Map<String, String> retiredKeys = new LinkedHashMap<>();

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public Map<String, String> getRetiredKeys() {
        return retiredKeys;
    }

    public void setRetiredKeys(Map<String, String> retiredKeys) {
        this.retiredKeys = retiredKeys == null ? new LinkedHashMap<>() : retiredKeys;
    }

    public boolean isConfigured() {
        return keyId != null && !keyId.isBlank() && key != null && !key.isBlank();
    }

    public SealingKeyring toKeyring() {
        if (!isConfigured()) {
            throw new IllegalStateException("A sealing key and its id must both be set");
        }
        if (retiredKeys.containsKey(keyId)) {
            throw new IllegalArgumentException("Retired sealing key '" + keyId + "' reuses the current id");
        }
        Map<String, byte[]> keys = new LinkedHashMap<>();
        retiredKeys.forEach((id, value) -> keys.put(id, decode(id, value)));
        keys.put(keyId, decode(keyId, key));
        return new SealingKeyring(keyId, keys);
    }

    @Override
    public String toString() {
        return "SealingKeyProperties[keyId=" + keyId + ", retiredKeyIds=" + retiredKeys.keySet() + "]";
    }

    // The decoder's message quotes the offending character, a piece of the secret: it is not chained.
    private static byte[] decode(String id, String value) {
        try {
            return Base64.getDecoder().decode(value == null ? "" : value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Sealing key '" + id + "' is not valid Base64");
        }
    }
}
