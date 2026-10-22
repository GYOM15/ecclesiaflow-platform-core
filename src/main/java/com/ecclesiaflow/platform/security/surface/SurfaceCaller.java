package com.ecclesiaflow.platform.security.surface;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Tells a decision point whether the caller is the web console. Outside the console a {@code member} operation
 * runs with admin powers capped: a mobile token, valid for months, must not carry an owner's console rights.
 */
public final class SurfaceCaller {

    private SurfaceCaller() {
    }

    /** {@link #isConsoleCaller(Authentication)} for the request being served. */
    public static boolean isConsoleCaller() {
        return isConsoleCaller(SecurityContextHolder.getContext().getAuthentication());
    }

    /**
     * {@code true} only for a token whose authorities carry {@code ef:surface:full}, the same test that opens
     * a console operation; {@code false} without a token, whatever else authenticated the call.
     */
    public static boolean isConsoleCaller(Authentication authentication) {
        return authentication instanceof JwtAuthenticationToken token
                && token.isAuthenticated()
                && Surface.CONSOLE.permits(token.getAuthorities());
    }
}
