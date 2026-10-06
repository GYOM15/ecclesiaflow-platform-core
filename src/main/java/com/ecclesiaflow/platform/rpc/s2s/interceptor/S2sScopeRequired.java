package com.ecclesiaflow.platform.rpc.s2s.interceptor;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Scope the caller's JWT must carry, on top of the generic {@code ef:s2s}, to invoke this RPC, e.g.
 * {@code @S2sScopeRequired("ef:members:write")}. Fail-closed: an unannotated business RPC is refused,
 * and {@link S2sScopeRegistry} fails the startup.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface S2sScopeRequired {

    String value();
}
