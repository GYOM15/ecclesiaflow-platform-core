package com.ecclesiaflow.platform.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * The REST plane's token rules, bound to {@code ecclesiaflow.rest.jwt.*};
 * {@code ecclesiaflow.rest.jwt.enabled=false} turns the shared decoder off.
 *
 * <p>{@code REST_JWT_AUDIENCE} binds to {@code rest.jwt.audience}, not to this prefix: a module that
 * lets it drive the audience declares
 * {@code ecclesiaflow.rest.jwt.audience=${REST_JWT_AUDIENCE:ecclesiaflow-internal}}.</p>
 */
@ConfigurationProperties(prefix = "ecclesiaflow.rest.jwt")
public class RestJwtProperties {

    public static final String DEFAULT_AUDIENCE = "ecclesiaflow-internal";

    /**
     * The {@code aud} entry every REST token must carry. Blank skips the audience check and keeps
     * the issuer pinned: an escape hatch for a migration window, not a setting to run on.
     */
    private String audience = DEFAULT_AUDIENCE;

    private final Jwks jwks = new Jwks();

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public Jwks getJwks() {
        return jwks;
    }

    /** How the realm's signing keys are kept, bound to {@code ecclesiaflow.rest.jwt.jwks.*}. */
    public static class Jwks {

        /** Age at which the key set is reloaded: a key removed from the realm is trusted until then. */
        private Duration refreshInterval = Duration.ofMinutes(5);

        /**
         * Shortest gap between two reloads, whether an unknown {@code kid} or a failed reload asks for one, so
         * that tokens with made-up key ids cannot turn every request into a call to Keycloak.
         */
        private Duration minReloadInterval = Duration.ofSeconds(30);

        /**
         * How long the last loaded key set stays in service while every reload fails, counted from the first
         * failure. Past it, tokens are refused rather than checked against keys the realm may have withdrawn.
         */
        private Duration outageTolerance = Duration.ofHours(24);

        /** Bounds a reload so that a hung Keycloak holds requests no longer than this, not indefinitely. */
        private Duration connectTimeout = Duration.ofSeconds(2);

        private Duration readTimeout = Duration.ofSeconds(2);

        public Duration getRefreshInterval() {
            return refreshInterval;
        }

        public void setRefreshInterval(Duration refreshInterval) {
            this.refreshInterval = refreshInterval;
        }

        public Duration getMinReloadInterval() {
            return minReloadInterval;
        }

        public void setMinReloadInterval(Duration minReloadInterval) {
            this.minReloadInterval = minReloadInterval;
        }

        public Duration getOutageTolerance() {
            return outageTolerance;
        }

        public void setOutageTolerance(Duration outageTolerance) {
            this.outageTolerance = outageTolerance;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }
    }
}
