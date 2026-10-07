package com.ecclesiaflow.platform.rpc.s2s;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

/** Server-to-server gRPC authentication; the library stays inert while {@code client-id} is unset. */
@ConfigurationProperties(prefix = "ecclesiaflow.platform.rpc.s2s")
@Validated
public class S2sProperties {

    /** Keycloak client id used for the {@code client_credentials} grant. */
    @NotBlank
    private String clientId;

    /** Inject from the environment, never hardcode. */
    @NotBlank
    private String clientSecret;

    /** Full token endpoint URL, e.g. {@code https://kc/realms/foo/protocol/openid-connect/token}. */
    @NotBlank
    private String tokenUrl;

    /** Full JWKS endpoint URL used by the receiving side to validate signatures. */
    @NotBlank
    private String jwksUri;

    /** Pinned on every inbound s2s token. */
    @NotBlank
    private String issuer;

    /**
     * Pins the inbound {@code aud}. Not a frontend/backend fence: the realm's audience mapper is on every
     * client, so frontend tokens carry it too; {@link #getAllowedAzp()} is what separates callers. Blank
     * skips the check (migration escape hatch).
     */
    private String expectedAudience = "ecclesiaflow-internal";

    /**
     * Client ids ({@code azp}) allowed on the inbound plane: list the backend service accounts, never the
     * frontend client. Empty leaves the check off; the posture is announced at startup either way.
     */
    private List<String> allowedAzp = new ArrayList<>();

    /**
     * Refuses to start while {@link #getAllowedAzp()} is empty, so a lost environment
     * variable cannot silently reopen the plane once the list is deployed.
     */
    private boolean requireAllowedAzp;

    /** Scope required on every inbound s2s RPC. */
    @NotBlank
    private String genericScope = "ef:s2s";

    /** Refreshes this many seconds before {@code exp}, so a token cannot expire mid-call. */
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

    public List<String> getAllowedAzp() {
        return allowedAzp;
    }

    public void setAllowedAzp(List<String> allowedAzp) {
        this.allowedAzp = allowedAzp == null ? new ArrayList<>() : allowedAzp;
    }

    public boolean isRequireAllowedAzp() {
        return requireAllowedAzp;
    }

    public void setRequireAllowedAzp(boolean requireAllowedAzp) {
        this.requireAllowedAzp = requireAllowedAzp;
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
