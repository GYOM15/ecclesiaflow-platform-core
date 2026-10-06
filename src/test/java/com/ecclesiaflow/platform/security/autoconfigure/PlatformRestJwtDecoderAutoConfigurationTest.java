package com.ecclesiaflow.platform.security.autoconfigure;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.io.IOException;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The REST plane's decoder, shared so that auth, members, church and communication stop each
 * carrying a copy: a pinned issuer, and a required audience unless the property is left blank.
 */
class PlatformRestJwtDecoderAutoConfigurationTest {

    private static final String ISSUER = "https://kc.example/realms/ecclesiaflow";
    private static final String FORGED_ISSUER = "https://evil.example/realms/forged";

    @Nested
    @DisplayName("the token validator")
    class Validator {

        private boolean accepts(String expectedAudience, Jwt jwt) {
            OAuth2TokenValidator<Jwt> validator =
                    PlatformRestJwtDecoderAutoConfiguration.restTokenValidator(ISSUER, expectedAudience);
            return !validator.validate(jwt).hasErrors();
        }

        @Test
        @DisplayName("the default audience accepts what the modules accepted before")
        void defaultAudience() {
            assertThat(accepts("ecclesiaflow-internal", jwt(ISSUER, List.of("account", "ecclesiaflow-internal"))))
                    .isTrue();
        }

        @Test
        @DisplayName("once set to ecclesiaflow-app, a token carrying only ecclesiaflow-internal is refused")
        void endStateAudience() {
            assertThat(accepts("ecclesiaflow-app", jwt(ISSUER, List.of("account", "ecclesiaflow-internal")))).isFalse();
            assertThat(accepts("ecclesiaflow-app", jwt(ISSUER, List.of("account", "ecclesiaflow-app")))).isTrue();
        }

        @Test
        @DisplayName("another issuer is refused whatever the audience")
        void otherIssuer() {
            assertThat(accepts("ecclesiaflow-internal", jwt(FORGED_ISSUER, List.of("ecclesiaflow-internal")))).isFalse();
        }

        @Test
        @DisplayName("a token with no audience claim is refused")
        void noAudienceClaim() {
            assertThat(accepts("ecclesiaflow-internal", jwt(ISSUER, null))).isFalse();
        }

        @Test
        @DisplayName("a blank or unset audience skips only the audience check, never the issuer")
        void blankAudience() {
            assertThat(accepts("", jwt(ISSUER, null))).isTrue();
            assertThat(accepts("", jwt(FORGED_ISSUER, null))).isFalse();
            assertThat(accepts(null, jwt(ISSUER, null))).isTrue();
            assertThat(accepts(null, jwt(FORGED_ISSUER, null))).isFalse();
        }

        @Test
        @DisplayName("the refusal names the expected audience, never the token's own")
        void refusalDescription() {
            OAuth2TokenValidator<Jwt> validator =
                    PlatformRestJwtDecoderAutoConfiguration.restTokenValidator(ISSUER, "ecclesiaflow-app");

            assertThat(validator.validate(jwt(ISSUER, List.of("realm-management"))).getErrors())
                    .singleElement()
                    .satisfies(error -> {
                        assertThat(error.getErrorCode()).isEqualTo("invalid_token");
                        assertThat(error.getDescription())
                                .isEqualTo("The required audience ecclesiaflow-app is missing")
                                .doesNotContain("realm-management");
                    });
        }

        private static Jwt jwt(String issuer, List<String> audience) {
            Jwt.Builder builder = Jwt.withTokenValue("token")
                    .header("alg", "RS256")
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(300))
                    .issuer(issuer)
                    .claim("azp", "ecclesiaflow-frontend");
            if (audience != null) {
                builder.audience(audience);
            }
            return builder.build();
        }
    }

    @Nested
    @DisplayName("the decoder bean")
    class DecoderBean {

        private static RSAKey signingKey;
        private static MockWebServer keycloak;

        private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PlatformRestJwtDecoderAutoConfiguration.class))
                .withPropertyValues(
                        "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + ISSUER,
                        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=" + keycloak.url("/certs"));

        @BeforeAll
        static void startKeycloak() throws JOSEException, IOException {
            signingKey = new RSAKeyGenerator(2048).keyID("rest-key").generate();
            keycloak = new MockWebServer();
            String jwks = new JWKSet(signingKey.toPublicJWK()).toString();
            keycloak.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
                @Override
                public MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest request) {
                    return new MockResponse().setHeader("Content-Type", "application/json").setBody(jwks);
                }
            });
            keycloak.start();
        }

        @AfterAll
        static void stopKeycloak() throws IOException {
            keycloak.shutdown();
        }

        @Test
        @DisplayName("is published as restJwtDecoder, and requires ecclesiaflow-internal by default")
        void defaultDecoder() {
            runner.run(context -> {
                assertThat(context).hasSingleBean(JwtDecoder.class).hasBean("restJwtDecoder");
                JwtDecoder decoder = context.getBean(JwtDecoder.class);

                assertThat(decoder.decode(signed(ISSUER, List.of("account", "ecclesiaflow-internal"))).getSubject())
                        .isEqualTo("member-sub");
                assertThatThrownBy(() -> decoder.decode(signed(ISSUER, List.of("account"))))
                        .isInstanceOf(JwtValidationException.class)
                        .hasMessageContaining("The required audience ecclesiaflow-internal is missing");
                assertThatThrownBy(() -> decoder.decode(signed(FORGED_ISSUER, List.of("ecclesiaflow-internal"))))
                        .isInstanceOf(JwtValidationException.class);
            });
        }

        @Test
        @DisplayName("requires the audience the module configures")
        void configuredAudience() {
            runner.withPropertyValues("ecclesiaflow.rest.jwt.audience=ecclesiaflow-app").run(context -> {
                JwtDecoder decoder = context.getBean(JwtDecoder.class);

                assertThat(decoder.decode(signed(ISSUER, List.of("ecclesiaflow-app"))).getSubject())
                        .isEqualTo("member-sub");
                assertThatThrownBy(() -> decoder.decode(signed(ISSUER, List.of("ecclesiaflow-internal"))))
                        .isInstanceOf(JwtValidationException.class);
            });
        }

        @Test
        @DisplayName("a blank audience still pins the issuer")
        void blankAudience() {
            runner.withPropertyValues("ecclesiaflow.rest.jwt.audience=").run(context -> {
                JwtDecoder decoder = context.getBean(JwtDecoder.class);

                assertThat(decoder.decode(signed(ISSUER, List.of("account"))).getSubject()).isEqualTo("member-sub");
                assertThatThrownBy(() -> decoder.decode(signed(FORGED_ISSUER, List.of("account"))))
                        .isInstanceOf(JwtValidationException.class);
            });
        }

        @Test
        @DisplayName("a module's own decoder takes precedence")
        void moduleDecoderWins() {
            JwtDecoder own = NimbusJwtDecoder.withJwkSetUri("https://kc.example/certs").build();

            runner.withBean("restJwtDecoder", JwtDecoder.class, () -> own)
                    .run(context -> assertThat(context.getBean(JwtDecoder.class)).isSameAs(own));
        }

        @Test
        @DisplayName("turned off, it leaves the decoder to Spring Boot")
        void disabled() {
            runner.withPropertyValues("ecclesiaflow.rest.jwt.enabled=false")
                    .run(context -> assertThat(context).doesNotHaveBean(JwtDecoder.class));
        }

        @Test
        @DisplayName("stays away from a module that is not a resource server")
        void noJwkSetUri() {
            new WebApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(PlatformRestJwtDecoderAutoConfiguration.class))
                    .run(context -> assertThat(context).doesNotHaveBean(JwtDecoder.class));
        }

        @Test
        @DisplayName("refuses to start without an issuer to pin")
        void missingIssuer() {
            new WebApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(PlatformRestJwtDecoderAutoConfiguration.class))
                    .withPropertyValues("spring.security.oauth2.resourceserver.jwt.jwk-set-uri=" + keycloak.url("/certs"))
                    .run(context -> assertThat(context).getFailure()
                            .rootCause()
                            .hasMessageContaining("spring.security.oauth2.resourceserver.jwt.issuer-uri"));
        }

        @Test
        @DisplayName("Spring Boot's own decoder backs off in its favour, and takes over when it is turned off")
        void bootDecoderBacksOff() {
            WebApplicationContextRunner withBoot = runner.withConfiguration(AutoConfigurations.of(
                    SecurityAutoConfiguration.class, OAuth2ResourceServerAutoConfiguration.class));

            withBoot.run(context -> {
                assertThat(context).hasSingleBean(JwtDecoder.class).hasBean("restJwtDecoder");
                assertThatThrownBy(() -> context.getBean(JwtDecoder.class).decode(signed(ISSUER, List.of("account"))))
                        .isInstanceOf(JwtValidationException.class);
            });
            withBoot.withPropertyValues("ecclesiaflow.rest.jwt.enabled=false").run(context -> {
                assertThat(context).hasSingleBean(JwtDecoder.class).doesNotHaveBean("restJwtDecoder");
                assertThat(context.getBean(JwtDecoder.class).decode(signed(ISSUER, List.of("account"))).getSubject())
                        .isEqualTo("member-sub");
            });
        }

        private static String signed(String issuer, List<String> audience) throws JOSEException {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .audience(audience)
                    .subject("member-sub")
                    .issueTime(new Date())
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("rest-key").build(), claims);
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        }
    }
}
