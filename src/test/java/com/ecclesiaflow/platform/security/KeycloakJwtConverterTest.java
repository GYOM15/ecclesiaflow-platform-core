package com.ecclesiaflow.platform.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakJwtConverterTest {

    private final KeycloakJwtConverter converter = new KeycloakJwtConverter();

    private static Jwt jwtWith(Map<String, Object> claims) {
        return new Jwt(
                "tok", Instant.now(), Instant.now().plusSeconds(60),
                Map.of("alg", "none"), claims);
    }

    @Test
    void extractsDirectRolesClaim() {
        AbstractAuthenticationToken token = converter.convert(jwtWith(
                Map.of("roles", List.of("USER", "ADMIN"))));
        assertThat(token.getAuthorities()).extracting("authority")
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void extractsRealmAccessRoles() {
        AbstractAuthenticationToken token = converter.convert(jwtWith(
                Map.of("realm_access", Map.of("roles", List.of("SUPER_ADMIN")))));
        assertThat(token.getAuthorities()).extracting("authority").containsExactly("ROLE_SUPER_ADMIN");
    }

    @Test
    void extractsResourceAccessRoles() {
        AbstractAuthenticationToken token = converter.convert(jwtWith(
                Map.of("resource_access", Map.of(
                        "ecclesiaflow-frontend", Map.of("roles", List.of("VIEWER")),
                        "other-client", "ignored"))));
        assertThat(token.getAuthorities()).extracting("authority").containsExactly("ROLE_VIEWER");
    }

    @Test
    void mergesAndDeduplicatesAcrossSources() {
        AbstractAuthenticationToken token = converter.convert(jwtWith(Map.of(
                "roles", List.of("USER"),
                "realm_access", Map.of("roles", List.of("USER", "ADMIN")),
                "resource_access", Map.of("c", Map.of("roles", List.of("ADMIN"))))));
        assertThat(token.getAuthorities()).extracting("authority")
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void doesNotDoublePrefixExistingRolePrefix() {
        AbstractAuthenticationToken token = converter.convert(jwtWith(
                Map.of("roles", List.of("ROLE_USER"))));
        assertThat(token.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
    }

    @Test
    void usesEmailAsPrincipalWhenAvailable() {
        AbstractAuthenticationToken token = converter.convert(jwtWith(
                Map.of("sub", "uuid", "email", "alice@x.com")));
        assertThat(token.getName()).isEqualTo("alice@x.com");
    }

    @Test
    void fallsBackToSubjectWhenEmailMissingOrBlank() {
        AbstractAuthenticationToken token = converter.convert(jwtWith(Map.of("sub", "uuid-abc")));
        assertThat(token.getName()).isEqualTo("uuid-abc");
    }

    @Test
    void emptyClaimsProduceNoAuthorities() {
        AbstractAuthenticationToken token = converter.convert(jwtWith(Map.of("sub", "uuid")));
        assertThat(token.getAuthorities()).isEmpty();
    }

    @Test
    void nonListRolesClaimIsIgnored() {
        AbstractAuthenticationToken token = converter.convert(jwtWith(
                Map.of("roles", "not-a-list", "sub", "u")));
        assertThat(token.getAuthorities()).isEmpty();
    }

    @Test
    void nonListRealmRolesAreIgnored() {
        AbstractAuthenticationToken token = converter.convert(jwtWith(
                Map.of("realm_access", Map.of("roles", "not-a-list"), "sub", "u")));
        assertThat(token.getAuthorities()).isEmpty();
    }

    @Test
    void resourceAccessWithoutRolesIsIgnored() {
        AbstractAuthenticationToken token = converter.convert(jwtWith(
                Map.of("resource_access", Map.of("c", Map.of()), "sub", "u")));
        assertThat(token.getAuthorities()).isEmpty();
    }
}
