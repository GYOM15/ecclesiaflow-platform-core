package com.ecclesiaflow.platform.rpc.s2s.interceptor;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.util.Objects;

/**
 * Deliberately not a {@link JwtDecoder}: a bean of that type is what Spring Boot's resource server and
 * every by-type injection resolve for the REST plane, where the s2s rules must never apply by accident.
 */
public final class S2sJwtDecoder {

    private final JwtDecoder delegate;

    public S2sJwtDecoder(JwtDecoder delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public Jwt decode(String token) throws JwtException {
        return delegate.decode(token);
    }
}
