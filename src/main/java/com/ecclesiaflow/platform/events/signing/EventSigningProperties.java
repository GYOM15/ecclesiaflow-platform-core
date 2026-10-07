package com.ecclesiaflow.platform.events.signing;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * HMAC signing of cross-module RabbitMQ domain events. Publishers sign whenever the secret is set;
 * {@code verify-signatures} only governs consumers.
 */
@ConfigurationProperties(prefix = "ecclesiaflow.events")
public class EventSigningProperties {

    /**
     * Blank disables both signing and verification (migration escape hatch). Must be identical in every
     * module that publishes or consumes domain events; inject it from the environment (32+ bytes).
     */
    private String hmacSecret = "";

    /**
     * {@code false} accepts a missing or invalid signature with a warning, so consumers can ship before
     * publishers sign. Flip to {@code true} once every publisher signs.
     */
    private boolean verifySignatures = false;

    public String getHmacSecret() {
        return hmacSecret;
    }

    public void setHmacSecret(String hmacSecret) {
        this.hmacSecret = hmacSecret;
    }

    public boolean isVerifySignatures() {
        return verifySignatures;
    }

    public void setVerifySignatures(boolean verifySignatures) {
        this.verifySignatures = verifySignatures;
    }

    public boolean isSigningEnabled() {
        return hmacSecret != null && !hmacSecret.isBlank();
    }
}
