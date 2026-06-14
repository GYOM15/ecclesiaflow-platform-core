package com.ecclesiaflow.platform.rpc.s2s.interceptor;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the OAuth2 scope a caller's JWT must carry to invoke a given
 * gRPC RPC. Placed on the Java method of a {@code BindableService}
 * implementation; the platform's {@link S2sScopeRegistry} reads the
 * annotation at startup and {@link S2sAuthServerInterceptor} enforces
 * the resulting scope on every inbound call.
 *
 * <p>The mandatory generic platform scope (default {@code ef:s2s}) is
 * checked independently and always required. This annotation adds a
 * second, RPC-specific scope on top — for example
 * {@code @S2sScopeRequired("ef:members:write")} on a method that
 * mutates member state.</p>
 *
 * <p>Enforcement is <strong>fail-closed</strong>: a business RPC that is
 * not annotated maps to no scope and is rejected, not allowed through on
 * the generic scope alone. The only exception is the gRPC standard
 * infrastructure services (Health, Reflection) — whose source code we
 * don't own and cannot annotate — which {@link S2sScopeRegistry} exempts
 * by service name so health probes and reflection tooling keep working.</p>
 *
 * @see S2sScopeRegistry
 * @see S2sAuthServerInterceptor
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface S2sScopeRequired {

    /** The scope claim value the caller's JWT must contain, e.g. {@code "ef:members:write"}. */
    String value();
}
