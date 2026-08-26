package com.ecclesiaflow.platform.events.signing;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for HMAC signing of cross-module RabbitMQ domain events
 * (security finding C07 — forged domain events → account-takeover / phishing).
 *
 * <p>Bound to {@code ecclesiaflow.events.*} in the consumer's
 * {@code application.properties}. Two independent knobs, by design:</p>
 *
 * <ul>
 *   <li>{@link #getHmacSecret() hmac-secret} — the shared key. There is
 *       <strong>no weak default</strong>: a blank secret disables signing and
 *       verification entirely (a deliberate migration escape hatch, mirroring
 *       the s2s expected-audience hatch). Inject via env var, never hardcode.</li>
 *   <li>{@link #isVerifySignatures() verify-signatures} — the enforcement flag,
 *       <strong>default {@code false}</strong>. Consumers can deploy the
 *       verifier BEFORE publishers start signing without dropping messages:
 *       {@code false} accepts unsigned/bad messages (logging a warning),
 *       {@code true} rejects them. Flip to {@code true} only once every
 *       publisher signs.</li>
 * </ul>
 *
 * <p>Publish-side signing is independent of {@code verify-signatures} and is
 * driven only by whether the secret is present — so a publisher with a secret
 * configured signs by default.</p>
 */
@ConfigurationProperties(prefix = "ecclesiaflow.events")
public class EventSigningProperties {

    /**
     * Shared HMAC-SHA256 secret. Blank (the default) disables both signing and
     * verification — the migration escape hatch. Provide a high-entropy value
     * (>= 32 bytes recommended) via environment variable in every module that
     * publishes or consumes domain events; it MUST be identical across them.
     */
    private String hmacSecret = "";

    /**
     * When {@code true}, inbound domain events with a missing or invalid
     * signature are rejected. When {@code false} (default), they are accepted
     * and a warning is logged — lets consumers ship before publishers sign.
     * Ignored when {@link #getHmacSecret()} is blank (signing disabled).
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

    /**
     * Whether HMAC signing/verification is active at all. {@code false} when the
     * secret is unset or blank — the escape hatch that lets the whole fleet run
     * unsigned during migration.
     */
    public boolean isSigningEnabled() {
        return hmacSecret != null && !hmacSecret.isBlank();
    }
}
