package com.ecclesiaflow.platform.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Converts a Keycloak JWT into a Spring Security {@link JwtAuthenticationToken},
 * extracting roles from three locations:
 *
 * <ul>
 *   <li>The direct {@code roles} claim (used when a custom Keycloak mapper
 *       flattens roles to the top level).</li>
 *   <li>{@code realm_access.roles} — realm-level roles.</li>
 *   <li>{@code resource_access.&lt;client&gt;.roles} — per-client roles.</li>
 * </ul>
 *
 * Role names are prefixed with {@code ROLE_} as Spring Security requires; the
 * principal is the {@code email} claim if present, otherwise the {@code sub}.
 *
 * <p>Auto-registered as a Spring bean by
 * {@code com.ecclesiaflow.platform.security.autoconfigure.PlatformSecurityAutoConfiguration}.
 * Consumers can override by declaring their own bean of this type.
 */
public class KeycloakJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final String ROLE_PREFIX = "ROLE_";
    private static final String ROLES_CLAIM = "roles";
    private static final String REALM_ACCESS_CLAIM = "realm_access";
    private static final String RESOURCE_ACCESS_CLAIM = "resource_access";

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = extractAuthorities(jwt);
        return new JwtAuthenticationToken(jwt, authorities, extractPrincipalName(jwt));
    }

    private Collection<GrantedAuthority> extractAuthorities(Jwt jwt) {
        return Stream.of(
                        extractDirectRoles(jwt).stream(),
                        extractRealmAccessRoles(jwt).stream(),
                        extractResourceAccessRoles(jwt).stream())
                .flatMap(s -> s)
                .distinct()
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private Collection<GrantedAuthority> extractDirectRoles(Jwt jwt) {
        Object rolesObj = jwt.getClaim(ROLES_CLAIM);
        if (rolesObj instanceof List) {
            return ((List<String>) rolesObj).stream()
                    .map(role -> new SimpleGrantedAuthority(prefixRole(role)))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private Collection<GrantedAuthority> extractRealmAccessRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim(REALM_ACCESS_CLAIM);
        if (realmAccess == null) {
            return Collections.emptyList();
        }
        Object rolesObj = realmAccess.get("roles");
        if (rolesObj instanceof List) {
            return ((List<String>) rolesObj).stream()
                    .map(role -> new SimpleGrantedAuthority(prefixRole(role)))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private Collection<GrantedAuthority> extractResourceAccessRoles(Jwt jwt) {
        Map<String, Object> resourceAccess = jwt.getClaim(RESOURCE_ACCESS_CLAIM);
        if (resourceAccess == null) {
            return Collections.emptyList();
        }
        return resourceAccess.values().stream()
                .filter(Map.class::isInstance)
                .map(client -> (Map<String, Object>) client)
                .filter(client -> client.containsKey("roles"))
                .flatMap(client -> {
                    Object rolesObj = client.get("roles");
                    if (rolesObj instanceof List) {
                        return ((List<String>) rolesObj).stream();
                    }
                    return Stream.empty();
                })
                .map(role -> new SimpleGrantedAuthority(prefixRole(role)))
                .collect(Collectors.toList());
    }

    private String prefixRole(String role) {
        return role.startsWith(ROLE_PREFIX) ? role : ROLE_PREFIX + role;
    }

    private String extractPrincipalName(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        return (email != null && !email.isBlank()) ? email : jwt.getSubject();
    }
}
