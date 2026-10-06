package com.ecclesiaflow.platform.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Authorities: {@code SCOPE_} for each {@code scope}/{@code scp} entry; {@code ROLE_} for the top-level
 * {@code roles}, {@code realm_access.roles}, and the roles of the token's own client in
 * {@code resource_access.<azp>.roles}. The principal name is {@code sub}.
 */
public class KeycloakJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final String ROLE_PREFIX = "ROLE_";
    private static final String ROLES = "roles";
    private static final String REALM_ACCESS_CLAIM = "realm_access";
    private static final String RESOURCE_ACCESS_CLAIM = "resource_access";
    private static final String AUTHORIZED_PARTY_CLAIM = "azp";

    private final JwtGrantedAuthoritiesConverter scopeAuthorities = new JwtGrantedAuthoritiesConverter();

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>(scopeAuthorities.convert(jwt));
        addRoles(authorities, jwt.getClaims().get(ROLES));
        addRoles(authorities, rolesOf(jwt.getClaims().get(REALM_ACCESS_CLAIM)));
        addRoles(authorities, clientRolesOf(jwt));
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }

    // Every other client in resource_access (realm-management, account, ...) is ignored:
    // flattening them would let a role defined on any client become a platform role.
    private static Object clientRolesOf(Jwt jwt) {
        String azp = jwt.getClaimAsString(AUTHORIZED_PARTY_CLAIM);
        if (azp == null || !(jwt.getClaims().get(RESOURCE_ACCESS_CLAIM) instanceof Map<?, ?> clients)) {
            return null;
        }
        return rolesOf(clients.get(azp));
    }

    private static Object rolesOf(Object access) {
        return access instanceof Map<?, ?> map ? map.get(ROLES) : null;
    }

    private static void addRoles(Collection<GrantedAuthority> authorities, Object roles) {
        if (!(roles instanceof List<?> list)) {
            return;
        }
        for (Object role : list) {
            if (role instanceof String name && !name.isBlank()) {
                authorities.add(new SimpleGrantedAuthority(
                        name.startsWith(ROLE_PREFIX) ? name : ROLE_PREFIX + name));
            }
        }
    }
}
