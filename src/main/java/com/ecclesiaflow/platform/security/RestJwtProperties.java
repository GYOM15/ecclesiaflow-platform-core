package com.ecclesiaflow.platform.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

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

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }
}
