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
    void rejectsTokenCarryingSomeOtherAudience() {
        // RENAMED. This was called rejectsTokenWithoutExpectedAudience and its
        // comment read « a frontend/user token never carries
        // aud=ecclesiaflow-internal ». That is false: the realm attaches the
        // audience mapper to every client, frontend included
        // (realm-ecclesiaflow.json:284), so a user token carries exactly the same
        // audience as a module's service account. What this test really shows is
        // narrower — a token minted for an unrelated audience is refused.
        // See frontendTokenIsAcceptedByTheS2sValidatorToday for the part that was
        // being claimed and is not true (F052).
        OAuth2TokenValidator<Jwt> validator =
                PlatformRpcAutoConfiguration.s2sTokenValidator(ISSUER, AUDIENCE);

        Jwt jwt = jwt(ISSUER, List.of("account", "frontend-public"));

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertThat(result.hasErrors()).isTrue();
    }

    /**
     * Pins the fact, so nobody re-derives the comfortable belief from the test
     * names above: the s2s validator accepts a token minted for the FRONTEND
     * client, because the realm stamps {@code aud=ecclesiaflow-internal} on it
     * too. The audience is not the fence between the two planes — the {@code azp}
     * allow-list on {@link com.ecclesiaflow.platform.rpc.s2s.interceptor.S2sAuthServerInterceptor}
     * is (F042).
     *
     * <p>This assertion is expected to INVERT on the day the realm stops stamping
     * {@code ecclesiaflow-internal} on {@code ecclesiaflow-frontend} (F038). When
     * that lands, flip it to {@code isTrue()} and say so here — do not delete it.</p>
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
