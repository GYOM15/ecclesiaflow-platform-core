package com.ecclesiaflow.platform.security.surface;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SurfaceCallerTest {

    private static JwtAuthenticationToken token(String... authorities) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("s")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        return new JwtAuthenticationToken(jwt, AuthorityUtils.createAuthorityList(authorities));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a token carrying ef:surface:full is the console")
    void consoleToken() {
        SecurityContextHolder.getContext().setAuthentication(token(Surface.SURFACE_FULL));

        assertThat(SurfaceCaller.isConsoleCaller()).isTrue();
    }

    @Test
    @DisplayName("a token without it is not, moderation included")
    void otherTokens() {
        assertThat(SurfaceCaller.isConsoleCaller(token("SCOPE_openid"))).isFalse();
        assertThat(SurfaceCaller.isConsoleCaller(token(Surface.MODERATION_ACT, Surface.PLATFORM_MODERATOR)))
                .isFalse();
    }

    @Test
    @DisplayName("no authentication, or one that is not a token, is not the console whatever it claims")
    void noToken() {
        assertThat(SurfaceCaller.isConsoleCaller()).isFalse();
        assertThat(SurfaceCaller.isConsoleCaller(new TestingAuthenticationToken("u", "p", Surface.SURFACE_FULL)))
                .isFalse();
    }

    @Test
    @DisplayName("a token marked unauthenticated is not the console")
    void unauthenticatedToken() {
        JwtAuthenticationToken token = token(Surface.SURFACE_FULL);
        token.setAuthenticated(false);

        assertThat(SurfaceCaller.isConsoleCaller(token)).isFalse();
    }
}
