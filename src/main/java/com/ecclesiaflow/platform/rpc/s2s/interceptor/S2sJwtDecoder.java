package com.ecclesiaflow.platform.rpc.s2s.interceptor;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.util.Objects;

/**
 * Decoder for inbound server-to-server tokens.
 *
 * <p>Deliberately not a {@link JwtDecoder}: a bean of that type is what Spring Boot's
 * resource server and every by-type injection in a module resolve for the REST plane,
 * and the s2s validation rules must never be applied there by accident.</p>
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
