package com.ecclesiaflow.platform.rpc.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the issuer + audience validation that {@link PlatformRpcAutoConfiguration}
 * wires onto the s2s {@link org.springframework.security.oauth2.jwt.JwtDecoder} (H01/H02).
 *
 * <p>We validate decoded {@link Jwt}s directly against the composite validator,
 * which lets us assert iss/aud behaviour without standing up a JWKS endpoint —
 * signature + expiry are the decoder's job and are covered upstream by Nimbus.</p>
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
    void rejectsTokenWithoutExpectedAudience() {
        OAuth2TokenValidator<Jwt> validator =
                PlatformRpcAutoConfiguration.s2sTokenValidator(ISSUER, AUDIENCE);

        // A frontend/user token: right issuer, but it never carries aud=ecclesiaflow-internal.
        Jwt jwt = jwt(ISSUER, List.of("account", "frontend-public"));

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertThat(result.hasErrors()).isTrue();
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
