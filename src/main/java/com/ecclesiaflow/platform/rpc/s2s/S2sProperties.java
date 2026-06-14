package com.ecclesiaflow.platform.rpc.s2s;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for server-to-server gRPC authentication.
 *
 * <p>Bound to {@code ecclesiaflow.platform.rpc.s2s.*} in the consumer's
 * {@code application.properties}. The library only activates when
 * {@link #getClientId()} is set, so consumers can opt out by leaving the
 * property unset.</p>
 */
@ConfigurationProperties(prefix = "ecclesiaflow.platform.rpc.s2s")
@Validated
public class S2sProperties {

    /** Keycloak client id used for the {@code client_credentials} grant. */
    @NotBlank
    private String clientId;

    /** Keycloak client secret. Inject via env var, never hardcode. */
    @NotBlank
    private String clientSecret;

    /** Full token endpoint URL, e.g. {@code https://kc/realms/foo/protocol/openid-connect/token}. */
    @NotBlank
    private String tokenUrl;

    /** Full JWKS endpoint URL used by the receiving side to validate signatures. */
    @NotBlank
    private String jwksUri;

    /** Expected {@code iss} claim. Pinned on every inbound s2s token. */
    @NotBlank
    private String issuer;

    /**
     * Expected {@code aud} claim on inbound s2s tokens. The realm stamps
     * {@code aud=ecclesiaflow-internal} on backend service-account clients only,
     * so this acts as a hard fence keeping frontend/user tokens out of the gRPC
     * plane even if they somehow carry {@code ef:s2s}.
     *
     * <p>Leave <strong>blank</strong> to skip audience validation — an escape
     * hatch for the migration window before the realm re-import lands. The
     * default ({@code ecclesiaflow-internal}) <strong>enforces</strong> it.</p>
     */
    private String expectedAudience = "ecclesiaflow-internal";

    /** Scope required on every inbound s2s RPC. Defaults to {@code ef:s2s}. */
    @NotBlank
    private String genericScope = "ef:s2s";

    /**
     * Refresh window before access-token expiry. The provider refreshes
     * proactively this many seconds before the JWT's {@code exp} claim
     * to avoid mid-call expirations.
     */
    @Min(0)
    private int refreshLeewaySeconds = 30;

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getTokenUrl() {
        return tokenUrl;
    }

    public void setTokenUrl(String tokenUrl) {
        this.tokenUrl = tokenUrl;
    }

    public String getJwksUri() {
        return jwksUri;
    }

    public void setJwksUri(String jwksUri) {
        this.jwksUri = jwksUri;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getExpectedAudience() {
        return expectedAudience;
    }

    public void setExpectedAudience(String expectedAudience) {
        this.expectedAudience = expectedAudience;
    }

    public String getGenericScope() {
        return genericScope;
    }

    public void setGenericScope(String genericScope) {
        this.genericScope = genericScope;
    }

    public int getRefreshLeewaySeconds() {
        return refreshLeewaySeconds;
    }

    public void setRefreshLeewaySeconds(int refreshLeewaySeconds) {
        this.refreshLeewaySeconds = refreshLeewaySeconds;
    }
}
