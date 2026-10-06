package com.ecclesiaflow.platform.security.autoconfigure;

import com.ecclesiaflow.platform.security.RestJwtProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * The REST plane's decoder: Keycloak's keys, a pinned issuer, and the audience
 * {@code ecclesiaflow.rest.jwt.audience} requires. Spring Boot's own resource-server decoder pins
 * the issuer but checks no audience, so this one is registered ahead of it and it backs off. A
 * module that declares its own {@link JwtDecoder} keeps it.
 *
 * <p>The default audience, {@code ecclesiaflow-internal}, is the one the modules have enforced so
 * far, so adopting this bean changes nothing in either deploy order. The end state is
 * {@code ecclesiaflow-app}, which only end-user clients carry; it is set once the realm stamps it,
 * never as the default, which would refuse every signed-in user while the realm lags behind.</p>
 *
 * <p>Independent of the s2s decoder, which is not a {@link JwtDecoder} and never reaches this plane.</p>
 */
@AutoConfiguration(before = OAuth2ResourceServerAutoConfiguration.class)
@EnableConfigurationProperties({RestJwtProperties.class, OAuth2ResourceServerProperties.class})
@ConditionalOnProperty(prefix = "spring.security.oauth2.resourceserver.jwt", name = "jwk-set-uri")
@ConditionalOnProperty(prefix = "ecclesiaflow.rest.jwt", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class PlatformRestJwtDecoderAutoConfiguration {

    static final String ISSUER_PROPERTY = "spring.security.oauth2.resourceserver.jwt.issuer-uri";

    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    public JwtDecoder restJwtDecoder(OAuth2ResourceServerProperties resourceServer, RestJwtProperties rest) {
        OAuth2ResourceServerProperties.Jwt jwt = resourceServer.getJwt();
        if (!StringUtils.hasText(jwt.getIssuerUri())) {
            throw new IllegalStateException(ISSUER_PROPERTY + " must be set: the REST decoder pins the issuer");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwt.getJwkSetUri()).build();
        decoder.setJwtValidator(restTokenValidator(jwt.getIssuerUri(), rest.getAudience()));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> restTokenValidator(String issuer, String expectedAudience) {
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(JwtValidators.createDefaultWithIssuer(issuer));
        validators.add(new JwtIssuerValidator(issuer));
        if (StringUtils.hasText(expectedAudience)) {
            validators.add(requiredAudience(expectedAudience));
        }
        return new DelegatingOAuth2TokenValidator<>(validators);
    }

    // The expected value is named, the token's own audiences are not: a 401 cannot be used to list
    // what the realm issues.
    private static OAuth2TokenValidator<Jwt> requiredAudience(String expected) {
        OAuth2Error missing = new OAuth2Error("invalid_token", "The required audience " + expected + " is missing", null);
        return jwt -> {
            List<String> audiences = jwt.getAudience();
            return audiences != null && audiences.contains(expected)
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(missing);
        };
    }
}
