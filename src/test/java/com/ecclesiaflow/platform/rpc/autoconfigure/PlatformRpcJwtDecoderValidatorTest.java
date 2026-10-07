package com.ecclesiaflow.platform.rpc.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs decoded tokens through the composite validator, without a JWKS endpoint: signature and expiry
 * are Nimbus's job, issuer and audience are what this library adds.
 */
class PlatformRpcJwtDecoderValidatorTest {

    private static final String ISSUER = "https://kc.example/realms/ecclesiaflow";
    private static final String AUDIENCE = "ecclesiaflow-internal";

    @Test
    void rejectsTokenFromDifferentIssuer() {
        OAuth2TokenValidator<Jwt> validator =
                PlatformRpcAutoConfiguration.s2sTokenValidator(ISSUER, AUDIENCE);

        Jwt jwt = jwt("https://evil.example/realms/forged", List.of(AUDIENCE));

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertThat(result.hasErrors()).isTrue();
    }

    @Test
    void rejectsTokenCarryingSomeOtherAudience() {
        // The realm stamps the internal audience on every client, frontend included, so this
        // only proves that a token minted for an unrelated audience is refused.
        OAuth2TokenValidator<Jwt> validator =
                PlatformRpcAutoConfiguration.s2sTokenValidator(ISSUER, AUDIENCE);

        Jwt jwt = jwt(ISSUER, List.of("account", "frontend-public"));

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertThat(result.hasErrors()).isTrue();
    }

    /**
     * The realm stamps {@code aud=ecclesiaflow-internal} on the frontend client too, so the s2s validator
     * accepts a user token: the {@code azp} allow-list is the fence, not the audience. Flip this to
     * {@code isTrue()} once the realm stops stamping it on {@code ecclesiaflow-frontend}; do not delete it.
     */
    @Test
    void frontendTokenIsAcceptedByTheS2sValidatorToday() {
        OAuth2TokenValidator<Jwt> validator =
                PlatformRpcAutoConfiguration.s2sTokenValidator(ISSUER, AUDIENCE);

        // What a logged-in user's token actually looks like against this realm:
        // issued by the same issuer, and carrying the internal audience.
        Jwt frontendToken = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .issuer(ISSUER)
                .audience(List.of("account", AUDIENCE))
                .claim("azp", "ecclesiaflow-frontend")
                .claim("scope", "openid profile email")
                .build();

        OAuth2TokenValidatorResult result = validator.validate(frontendToken);

        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void rejectsTokenWithNoAudienceClaimAtAll() {
        OAuth2TokenValidator<Jwt> validator =
                PlatformRpcAutoConfiguration.s2sTokenValidator(ISSUER, AUDIENCE);

        Jwt jwt = jwt(ISSUER, null);

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertThat(result.hasErrors()).isTrue();
    }

    @Test
    void acceptsValidInternalToken() {
        OAuth2TokenValidator<Jwt> validator =
                PlatformRpcAutoConfiguration.s2sTokenValidator(ISSUER, AUDIENCE);

        // A backend service-account token: correct issuer AND aud=ecclesiaflow-internal.
        Jwt jwt = jwt(ISSUER, List.of("account", AUDIENCE));

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void blankExpectedAudienceSkipsAudienceCheck() {
        // Escape hatch for the migration window: with the audience left blank, a
        // token missing aud must still pass (issuer is still pinned).
        OAuth2TokenValidator<Jwt> validator =
                PlatformRpcAutoConfiguration.s2sTokenValidator(ISSUER, "");

        Jwt jwt = jwt(ISSUER, null);

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void blankExpectedAudienceStillPinsIssuer() {
        // The escape hatch only drops the audience check — issuer stays enforced.
        OAuth2TokenValidator<Jwt> validator =
                PlatformRpcAutoConfiguration.s2sTokenValidator(ISSUER, "");

        Jwt jwt = jwt("https://evil.example/realms/forged", null);

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertThat(result.hasErrors()).isTrue();
    }

    private static Jwt jwt(String issuer, List<String> audience) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .issuer(issuer)
                .claim("scope", "ef:s2s");
        if (audience != null) {
            builder.audience(audience);
        }
        return builder.build();
    }
}
