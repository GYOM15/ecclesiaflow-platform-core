package com.ecclesiaflow.platform.rpc.s2s.interceptor;
import com.ecclesiaflow.platform.rpc.s2s.S2sProperties;


import com.ecclesiaflow.platform.rpc.events.S2sAuthEvents;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * gRPC server interceptor that authenticates every incoming RPC with a JWT and
 * enforces the generic platform scope (default {@code ef:s2s}).
 *
 * <p><strong>Single responsibility:</strong> validate the token, check the scope,
 * and decide whether to forward the call. No logging here — rejections are
 * announced through events, which the logging aspect picks up.</p>
 *
 * <p>Method-level scope enforcement (per-RPC scopes such as
 * {@code ef:members:write:internal}) is intentionally not implemented in 0.1.x.
 * See issue #1 for the v0.2.0 plan.</p>
 */
public class S2sAuthServerInterceptor implements ServerInterceptor {

    static final Metadata.Key<String> AUTHORIZATION_KEY =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final JwtDecoder jwtDecoder;
    private final String requiredScope;
    private final ApplicationEventPublisher events;

    public S2sAuthServerInterceptor(JwtDecoder jwtDecoder, S2sProperties props, ApplicationEventPublisher events) {
        this.jwtDecoder = jwtDecoder;
        this.requiredScope = props.getGenericScope();
        this.events = events;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        String fullMethodName = call.getMethodDescriptor().getFullMethodName();
        String authorization = headers.get(AUTHORIZATION_KEY);

        if (authorization == null || !authorization.startsWith("Bearer ")) {
            events.publishEvent(new S2sAuthEvents.InboundMissingHeader(fullMethodName));
            return abort(call, Status.UNAUTHENTICATED.withDescription("Missing or malformed Authorization header"));
        }

        String token = authorization.substring("Bearer ".length()).trim();
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(token);
        } catch (JwtException e) {
            events.publishEvent(new S2sAuthEvents.InboundInvalidToken(fullMethodName, e.getMessage()));
            return abort(call, Status.UNAUTHENTICATED.withDescription("Invalid token"));
        }

        Set<String> scopes = extractScopes(jwt);
        if (!scopes.contains(requiredScope)) {
            events.publishEvent(new S2sAuthEvents.InboundMissingScope(fullMethodName, requiredScope));
            return abort(call, Status.PERMISSION_DENIED.withDescription("Missing required scope: " + requiredScope));
        }

        return next.startCall(call, headers);
    }

    private static <ReqT, RespT> ServerCall.Listener<ReqT> abort(ServerCall<ReqT, RespT> call, Status status) {
        call.close(status, new Metadata());
        return new ServerCall.Listener<>() {};
    }

    /**
     * Extracts scopes from a Keycloak-issued JWT. Keycloak puts them in the
     * space-delimited {@code scope} claim (RFC 8693 style); some IDPs use the
     * {@code scp} array claim — we honor both.
     */
    private static Set<String> extractScopes(Jwt jwt) {
        Set<String> result = new HashSet<>();
        Object scopeClaim = jwt.getClaim("scope");
        if (scopeClaim instanceof String s && !s.isBlank()) {
            result.addAll(Arrays.asList(s.split("\\s+")));
        }
        Object scpClaim = jwt.getClaim("scp");
        if (scpClaim instanceof Collection<?> coll) {
            for (Object item : coll) {
                if (item instanceof String s) {
                    result.add(s);
                }
            }
        }
        return result.isEmpty() ? Collections.emptySet() : result;
    }
}
