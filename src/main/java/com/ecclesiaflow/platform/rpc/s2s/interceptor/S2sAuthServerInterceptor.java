package com.ecclesiaflow.platform.rpc.s2s.interceptor;
import com.ecclesiaflow.platform.rpc.s2s.S2sProperties;


import com.ecclesiaflow.platform.logging.SecurityMaskingUtils;
import com.ecclesiaflow.platform.rpc.events.S2sAuthEvents;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * gRPC server interceptor that authenticates every incoming RPC with a JWT,
 * enforces the generic platform scope (default {@code ef:s2s}), and — when
 * the called RPC is annotated with {@link S2sScopeRequired} — enforces a
 * second per-method scope on top.
 *
 * <p><strong>Single responsibility:</strong> validate the token, check the
 * scopes, and decide whether to forward the call. No logging here —
 * rejections are announced through events, which the logging aspect picks
 * up.</p>
 *
 * <p><strong>Fail-closed per-method scopes.</strong> An RPC that maps to no
 * required method-scope is <em>rejected</em>, not allowed through on the
 * generic scope alone. The sole exception is the small set of gRPC standard
 * infrastructure services (Health, Reflection) tracked by
 * {@link S2sScopeRegistry#isInfrastructureService(String)} — those we don't
 * own and cannot annotate, so they stay reachable by any caller that already
 * carries {@code ef:s2s} (health probes and reflection tooling would
 * otherwise break). Every real business RPC declares its scope via
 * {@link S2sScopeRequired}; a new business RPC added without the annotation
 * is denied by design until it is annotated.</p>
 *
 * <p><strong>Allowed authorized parties.</strong> On top of the two scope
 * checks, the interceptor can pin the token's {@code azp} claim — the Keycloak
 * client the token was minted for — to an explicit allow-list
 * ({@code ecclesiaflow.platform.rpc.s2s.allowed-azp}). This is the barrier that
 * does not depend on the realm: the audience claim is stamped on every client in
 * the realm, so {@code aud} alone does not separate a backend service account
 * from a frontend token, while the client id does. Empty list (the default)
 * leaves the check off, so existing deployments are unaffected until the
 * property is set (finding F042).</p>
 *
 * <p><strong>Accepted calls are announced too.</strong> Refusals alone leave a
 * successful lateral call between modules with no trace; every accept publishes
 * an {@link S2sAuthEvents.InboundAccepted} and increments
 * {@code ef_s2s_inbound_total} (finding F045).</p>
 */
public class S2sAuthServerInterceptor implements ServerInterceptor {

    static final Metadata.Key<String> AUTHORIZATION_KEY =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    /** Metric name, dotted per Micrometer convention; Prometheus renders it {@code ef_s2s_inbound_total}. */
    static final String METRIC = "ef.s2s.inbound";

    private static final String UNKNOWN_AZP = "unknown";

    private final JwtDecoder jwtDecoder;
    private final String requiredScope;
    private final Set<String> allowedAzp;
    private final ApplicationEventPublisher events;
    private final S2sScopeRegistry scopeRegistry;
    private final MeterRegistry meterRegistry;

    public S2sAuthServerInterceptor(JwtDecoder jwtDecoder,
                                    S2sProperties props,
                                    ApplicationEventPublisher events,
                                    S2sScopeRegistry scopeRegistry) {
        this(jwtDecoder, props, events, scopeRegistry, null);
    }

    /** @param meterRegistry may be {@code null} — the interceptor then counts nothing and still decides. */
    public S2sAuthServerInterceptor(JwtDecoder jwtDecoder,
                                    S2sProperties props,
                                    ApplicationEventPublisher events,
                                    S2sScopeRegistry scopeRegistry,
                                    MeterRegistry meterRegistry) {
        this.jwtDecoder = jwtDecoder;
        this.requiredScope = props.getGenericScope();
        this.allowedAzp = normalizeAzp(props.getAllowedAzp());
        this.events = events;
        this.scopeRegistry = scopeRegistry;
        this.meterRegistry = meterRegistry;
    }

    private static Set<String> normalizeAzp(List<String> configured) {
        if (configured == null || configured.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> result = new HashSet<>();
        for (String entry : configured) {
            if (entry != null && !entry.isBlank()) {
                result.add(entry.trim());
            }
        }
        return result;
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
            count(fullMethodName, UNKNOWN_AZP, "missing_header");
            return abort(call, Status.UNAUTHENTICATED.withDescription("Missing or malformed Authorization header"));
        }

        String token = authorization.substring("Bearer ".length()).trim();
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(token);
        } catch (JwtException e) {
            events.publishEvent(new S2sAuthEvents.InboundInvalidToken(fullMethodName, e.getMessage()));
            count(fullMethodName, UNKNOWN_AZP, "invalid_token");
            return abort(call, Status.UNAUTHENTICATED.withDescription("Invalid token"));
        }

        String azp = jwt.getClaimAsString("azp");
        String azpTag = (azp == null || azp.isBlank()) ? UNKNOWN_AZP : azp;
        String subject = SecurityMaskingUtils.maskId(jwt.getSubject());

        // 1. Authorized party (off by default). The realm stamps aud=ecclesiaflow-internal
        //    on every client, so the audience does not separate a backend service account
        //    from a frontend token — the client id does. Checked before the scopes: a token
        //    minted for something with no business on this plane is refused whatever it carries.
        if (!allowedAzp.isEmpty() && (azp == null || !allowedAzp.contains(azp))) {
            events.publishEvent(new S2sAuthEvents.InboundForeignClient(fullMethodName, azp));
            count(fullMethodName, azpTag, "foreign_client");
            return abort(call, Status.PERMISSION_DENIED.withDescription("Client not allowed on the s2s plane"));
        }

        Set<String> scopes = extractScopes(jwt);

        // 2. Generic platform scope (always required) — keeps non-backend clients out.
        if (!scopes.contains(requiredScope)) {
            events.publishEvent(new S2sAuthEvents.InboundMissingScope(fullMethodName, requiredScope));
            count(fullMethodName, azpTag, "missing_generic_scope");
            return abort(call, Status.PERMISSION_DENIED.withDescription("Missing required scope: " + requiredScope));
        }

        // 3. Per-method scope (fail-closed). The RPC must declare a scope via
        //    @S2sScopeRequired and the token must carry it. The only RPCs allowed
        //    through without a declared scope are the gRPC standard infrastructure
        //    services (Health, Reflection) we don't own and cannot annotate.
        String methodScope = scopeRegistry.requiredScope(fullMethodName).orElse(null);
        if (methodScope == null) {
            if (scopeRegistry.isInfrastructureService(fullMethodName)) {
                events.publishEvent(new S2sAuthEvents.InboundInfrastructureBypass(
                        fullMethodName, serviceNameOf(fullMethodName), subject, azp));
                count(fullMethodName, azpTag, "infrastructure_bypass");
                return next.startCall(call, headers);
            }
            events.publishEvent(new S2sAuthEvents.InboundUnmappedMethod(fullMethodName));
            count(fullMethodName, azpTag, "unmapped_method");
            return abort(call, Status.PERMISSION_DENIED.withDescription("No scope mapping for method"));
        }
        if (!scopes.contains(methodScope)) {
            events.publishEvent(new S2sAuthEvents.InboundMissingScope(fullMethodName, methodScope));
            count(fullMethodName, azpTag, "missing_method_scope");
            return abort(call, Status.PERMISSION_DENIED.withDescription("Missing required scope: " + methodScope));
        }

        events.publishEvent(new S2sAuthEvents.InboundAccepted(fullMethodName, subject, azp, methodScope));
        count(fullMethodName, azpTag, "accepted");
        return next.startCall(call, headers);
    }

    /** {@code pkg.Service/Method} → {@code pkg.Service}. */
    private static String serviceNameOf(String fullMethodName) {
        if (fullMethodName == null) {
            return "unknown";
        }
        int slash = fullMethodName.indexOf('/');
        return slash >= 0 ? fullMethodName.substring(0, slash) : fullMethodName;
    }

    /**
     * Both tags are closed sets — the gRPC methods this server exposes, and the
     * Keycloak clients that may reach it — so cardinality is bounded by the
     * deployment, not by traffic.
     */
    private void count(String fullMethodName, String azp, String outcome) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder(METRIC)
                .tag("method", fullMethodName == null ? "unknown" : fullMethodName)
                .tag("azp", azp)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
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
