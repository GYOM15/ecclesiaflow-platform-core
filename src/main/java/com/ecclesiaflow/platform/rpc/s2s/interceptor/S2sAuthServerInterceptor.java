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
import java.util.Set;

/**
 * Authenticates every inbound RPC: allowed {@code azp}, then the generic scope, then the per-method
 * scope declared with {@link S2sScopeRequired}. Fail-closed: a method with no declared scope is denied,
 * except the gRPC infrastructure services (Health, Reflection) that cannot be annotated. Every decision,
 * accepts included, is published as an event so a lateral call between modules leaves a trace.
 */
public class S2sAuthServerInterceptor implements ServerInterceptor {

    static final Metadata.Key<String> AUTHORIZATION_KEY =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    /** Exported to Prometheus as {@code ef_s2s_inbound_total}. */
    static final String METRIC = "ef.s2s.inbound";

    private static final String UNKNOWN_AZP = "unknown";

    private final JwtDecoder jwtDecoder;
    private final String requiredScope;
    private final S2sAzpAllowList allowedAzp;
    private final ApplicationEventPublisher events;
    private final S2sScopeRegistry scopeRegistry;
    private final MeterRegistry meterRegistry;

    public S2sAuthServerInterceptor(JwtDecoder jwtDecoder,
                                    S2sProperties props,
                                    ApplicationEventPublisher events,
                                    S2sScopeRegistry scopeRegistry) {
        this(jwtDecoder, props, events, scopeRegistry, null);
    }

    /** A {@code null} registry counts nothing; decisions are unchanged. */
    public S2sAuthServerInterceptor(JwtDecoder jwtDecoder,
                                    S2sProperties props,
                                    ApplicationEventPublisher events,
                                    S2sScopeRegistry scopeRegistry,
                                    MeterRegistry meterRegistry) {
        this.jwtDecoder = jwtDecoder;
        this.requiredScope = props.getGenericScope();
        this.allowedAzp = S2sAzpAllowList.from(props);
        this.events = events;
        this.scopeRegistry = scopeRegistry;
        this.meterRegistry = meterRegistry;
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

        // The realm stamps aud on every client, so only the client id separates a service account from a
        // frontend token. Checked before the scopes, whatever the token carries.
        if (!allowedAzp.permits(azp)) {
            events.publishEvent(new S2sAuthEvents.InboundForeignClient(fullMethodName, azp));
            count(fullMethodName, azpTag, "foreign_client");
            return abort(call, Status.PERMISSION_DENIED.withDescription("Client not allowed on the s2s plane"));
        }

        Set<String> scopes = extractScopes(jwt);

        if (!scopes.contains(requiredScope)) {
            events.publishEvent(new S2sAuthEvents.InboundMissingScope(fullMethodName, requiredScope));
            count(fullMethodName, azpTag, "missing_generic_scope");
            return abort(call, Status.PERMISSION_DENIED.withDescription("Missing required scope: " + requiredScope));
        }

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

    private static String serviceNameOf(String fullMethodName) {
        if (fullMethodName == null) {
            return "unknown";
        }
        int slash = fullMethodName.indexOf('/');
        return slash >= 0 ? fullMethodName.substring(0, slash) : fullMethodName;
    }

    /** Both tags are closed sets (exposed methods, realm clients), so cardinality is bounded by the deployment. */
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

    /** Keycloak uses the space-delimited {@code scope} claim; other IdPs use a {@code scp} array. */
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
