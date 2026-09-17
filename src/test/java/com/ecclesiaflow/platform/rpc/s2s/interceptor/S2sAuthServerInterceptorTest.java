package com.ecclesiaflow.platform.rpc.s2s.interceptor;

import com.ecclesiaflow.platform.rpc.s2s.S2sProperties;

import com.ecclesiaflow.platform.rpc.events.S2sAuthEvents;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S2sAuthServerInterceptorTest {

    private JwtDecoder decoder;
    private ApplicationEventPublisher events;
    @SuppressWarnings("rawtypes")
    private ServerCall call;
    @SuppressWarnings("rawtypes")
    private ServerCallHandler next;
    @SuppressWarnings("rawtypes")
    private MethodDescriptor methodDescriptor;
    private S2sProperties props;
    private S2sScopeRegistry scopeRegistry;
    private MeterRegistry meters;
    private S2sAuthServerInterceptor interceptor;

    @BeforeEach
    void setUp() {
        decoder = mock(JwtDecoder.class);
        events = mock(ApplicationEventPublisher.class);
        call = mock(ServerCall.class);
        next = mock(ServerCallHandler.class);
        methodDescriptor = mock(MethodDescriptor.class);
        scopeRegistry = mock(S2sScopeRegistry.class);
        when(call.getMethodDescriptor()).thenReturn(methodDescriptor);
        when(methodDescriptor.getFullMethodName()).thenReturn("test.Service/Method");
        // By default, no per-method scope is required (most tests only exercise the
        // generic check). The two tests that need a method-specific scope override
        // this stub locally.
        when(scopeRegistry.requiredScope(anyString())).thenReturn(java.util.Optional.empty());

        props = new S2sProperties();
        props.setClientId("ecclesiaflow-backend");
        props.setClientSecret("x");
        props.setTokenUrl("http://kc/token");
        props.setJwksUri("http://kc/jwks");
        props.setIssuer("http://kc");
        props.setExpectedAudience("ecclesiaflow-internal");
        props.setGenericScope("ef:s2s");

        meters = new SimpleMeterRegistry();
        interceptor = new S2sAuthServerInterceptor(decoder, props, events, scopeRegistry, meters);
    }

    /** Count on {@code ef.s2s.inbound} for one outcome, 0 when the meter was never created. */
    private double counted(String outcome) {
        io.micrometer.core.instrument.Counter counter = meters.find(S2sAuthServerInterceptor.METRIC)
                .tag("outcome", outcome)
                .counter();
        return counter == null ? 0d : counter.count();
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsCallWithoutAuthorizationHeader() {
        Metadata headers = new Metadata();

        interceptor.interceptCall(call, headers, next);

        ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
        verify(call).close(status.capture(), any(Metadata.class));
        assertThat(status.getValue().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);

        ArgumentCaptor<Object> evt = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(evt.capture());
        assertThat(evt.getValue()).isInstanceOf(S2sAuthEvents.InboundMissingHeader.class);

        verify(next, never()).startCall(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsCallWithMalformedAuthorizationHeader() {
        Metadata headers = new Metadata();
        headers.put(S2sAuthServerInterceptor.AUTHORIZATION_KEY, "Basic abc123");

        interceptor.interceptCall(call, headers, next);

        ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
        verify(call).close(status.capture(), any(Metadata.class));
        assertThat(status.getValue().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
        verify(events).publishEvent(any(S2sAuthEvents.InboundMissingHeader.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsCallWithInvalidJwt() {
        when(decoder.decode("bad-token")).thenThrow(new JwtException("signature mismatch"));

        Metadata headers = new Metadata();
        headers.put(S2sAuthServerInterceptor.AUTHORIZATION_KEY, "Bearer bad-token");

        interceptor.interceptCall(call, headers, next);

        ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
        verify(call).close(status.capture(), any(Metadata.class));
        assertThat(status.getValue().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);

        ArgumentCaptor<Object> evt = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(evt.capture());
        assertThat(evt.getValue())
                .isInstanceOf(S2sAuthEvents.InboundInvalidToken.class)
                .satisfies(e -> {
                    S2sAuthEvents.InboundInvalidToken parsed = (S2sAuthEvents.InboundInvalidToken) e;
                    assertThat(parsed.reason()).isEqualTo("signature mismatch");
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsTokenMissingRequiredScope() {
        Jwt jwt = jwt(Map.of("scope", "openid profile"));
        when(decoder.decode("good-but-wrong-scope")).thenReturn(jwt);

        Metadata headers = new Metadata();
        headers.put(S2sAuthServerInterceptor.AUTHORIZATION_KEY, "Bearer good-but-wrong-scope");

        interceptor.interceptCall(call, headers, next);

        ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
        verify(call).close(status.capture(), any(Metadata.class));
        assertThat(status.getValue().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
        verify(events).publishEvent(any(S2sAuthEvents.InboundMissingScope.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptsTokenWithRequiredScopeInScopeClaim() {
        // Verifies scope extraction from the space-delimited `scope` claim. The
        // RPC declares ef:email:send, which the token carries — so it passes the
        // fail-closed per-method check too.
        Jwt jwt = jwt(Map.of("scope", "ef:s2s ef:email:send"));
        when(decoder.decode("valid")).thenReturn(jwt);
        when(scopeRegistry.requiredScope("test.Service/Method"))
                .thenReturn(java.util.Optional.of("ef:email:send"));

        Metadata headers = new Metadata();
        headers.put(S2sAuthServerInterceptor.AUTHORIZATION_KEY, "Bearer valid");

        interceptor.interceptCall(call, headers, next);

        verify(next).startCall(eq(call), eq(headers));
        verify(call, never()).close(any(), any());
        // INVERTED. It asserted that an accepted call publishes NOTHING, which is
        // the finding: every refusal was announced and every success was silent,
        // so a lateral call between modules left no trace at all (F045).
        verify(events).publishEvent(any(S2sAuthEvents.InboundAccepted.class));
        assertThat(counted("accepted")).isEqualTo(1d);
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptsTokenWithRequiredScopeInScpClaimArray() {
        // Verifies scope extraction from the `scp` array claim. Same fail-closed
        // setup: the RPC declares ef:email:send and the token carries it.
        Jwt jwt = jwt(Map.of("scp", List.of("ef:s2s", "ef:email:send")));
        when(decoder.decode("valid")).thenReturn(jwt);
        when(scopeRegistry.requiredScope("test.Service/Method"))
                .thenReturn(java.util.Optional.of("ef:email:send"));

        Metadata headers = new Metadata();
        headers.put(S2sAuthServerInterceptor.AUTHORIZATION_KEY, "Bearer valid");

        interceptor.interceptCall(call, headers, next);

        verify(next).startCall(eq(call), eq(headers));
        verify(events).publishEvent(any(S2sAuthEvents.InboundAccepted.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsTokenMissingMethodScope() {
        // Token has the generic ef:s2s but not the method-specific ef:members:write
        Jwt jwt = jwt(Map.of("scope", "ef:s2s ef:email:send"));
        when(decoder.decode("valid")).thenReturn(jwt);
        // The called RPC requires ef:members:write per its @S2sScopeRequired annotation.
        when(scopeRegistry.requiredScope("test.Service/Method"))
                .thenReturn(java.util.Optional.of("ef:members:write"));

        Metadata headers = new Metadata();
        headers.put(S2sAuthServerInterceptor.AUTHORIZATION_KEY, "Bearer valid");

        interceptor.interceptCall(call, headers, next);

        ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
        verify(call).close(status.capture(), any(Metadata.class));
        assertThat(status.getValue().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
        assertThat(status.getValue().getDescription()).contains("ef:members:write");
        verify(events).publishEvent(any(S2sAuthEvents.InboundMissingScope.class));
        verify(next, never()).startCall(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptsTokenWithBothGenericAndMethodScope() {
        Jwt jwt = jwt(Map.of("scope", "ef:s2s ef:members:write"));
        when(decoder.decode("valid")).thenReturn(jwt);
        when(scopeRegistry.requiredScope("test.Service/Method"))
                .thenReturn(java.util.Optional.of("ef:members:write"));

        Metadata headers = new Metadata();
        headers.put(S2sAuthServerInterceptor.AUTHORIZATION_KEY, "Bearer valid");

        interceptor.interceptCall(call, headers, next);

        verify(next).startCall(eq(call), eq(headers));
        verify(events).publishEvent(any(S2sAuthEvents.InboundAccepted.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsUnannotatedBusinessMethodFailClosed() {
        // A hypothetical business RPC that was added without @S2sScopeRequired:
        // it maps to no per-method scope and is not infrastructure. Fail-closed
        // means it is denied even though the token carries the generic ef:s2s.
        when(methodDescriptor.getFullMethodName()).thenReturn("ecclesiaflow.members.MembersService/SomeNewRpc");
        Jwt jwt = jwt(Map.of("scope", "ef:s2s"));
        when(decoder.decode("valid")).thenReturn(jwt);
        // requiredScope() returns empty (default stub) and isInfrastructureService()
        // returns false (mock default) — i.e. an unmapped business method.

        Metadata headers = new Metadata();
        headers.put(S2sAuthServerInterceptor.AUTHORIZATION_KEY, "Bearer valid");

        interceptor.interceptCall(call, headers, next);

        ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
        verify(call).close(status.capture(), any(Metadata.class));
        assertThat(status.getValue().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
        verify(events).publishEvent(any(S2sAuthEvents.InboundUnmappedMethod.class));
        verify(next, never()).startCall(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void allowsUnannotatedInfrastructureHealthService() {
        // The gRPC Health service is intentionally unannotated and must stay
        // reachable so liveness/readiness probes keep working. It maps to no
        // per-method scope but is whitelisted as infrastructure.
        when(methodDescriptor.getFullMethodName()).thenReturn("grpc.health.v1.Health/Check");
        when(scopeRegistry.isInfrastructureService("grpc.health.v1.Health/Check")).thenReturn(true);
        Jwt jwt = jwt(Map.of("scope", "ef:s2s"));
        when(decoder.decode("valid")).thenReturn(jwt);

        Metadata headers = new Metadata();
        headers.put(S2sAuthServerInterceptor.AUTHORIZATION_KEY, "Bearer valid");

        interceptor.interceptCall(call, headers, next);

        verify(next).startCall(eq(call), eq(headers));
        verify(call, never()).close(any(), any());
        // The bypass is announced too, but as its own event: the health probe
        // fires every few seconds and must not be logged at the same level as a
        // business call.
        verify(events).publishEvent(any(S2sAuthEvents.InboundInfrastructureBypass.class));
        assertThat(counted("infrastructure_bypass")).isEqualTo(1d);
    }

    // ========================================================================
    // Allowed authorized parties (F042) — the barrier that does not need the realm
    // ========================================================================

    @Test
    @SuppressWarnings("unchecked")
    void rejectsATokenMintedForAClientThatIsNotOnTheAllowList() {
        // The realm stamps aud=ecclesiaflow-internal on EVERY client, so a frontend
        // token that has somehow acquired ef:s2s passes the audience check and both
        // scope checks. The client id is what actually separates the two planes.
        props.setAllowedAzp(List.of("ecclesiaflow-church-backend", "ecclesiaflow-members-backend"));
        interceptor = new S2sAuthServerInterceptor(decoder, props, events, scopeRegistry, meters);
        Jwt jwt = jwt(Map.of("scope", "ef:s2s ef:members:read", "azp", "ecclesiaflow-frontend"));
        when(decoder.decode("valid")).thenReturn(jwt);
        when(scopeRegistry.requiredScope("test.Service/Method"))
                .thenReturn(java.util.Optional.of("ef:members:read"));

        Metadata headers = new Metadata();
        headers.put(S2sAuthServerInterceptor.AUTHORIZATION_KEY, "Bearer valid");

        interceptor.interceptCall(call, headers, next);

        ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
        verify(call).close(status.capture(), any(Metadata.class));
        assertThat(status.getValue().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
        verify(events).publishEvent(any(S2sAuthEvents.InboundForeignClient.class));
        verify(next, never()).startCall(any(), any());
        assertThat(counted("foreign_client")).isEqualTo(1d);
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptsATokenMintedForAnAllowedClient() {
        props.setAllowedAzp(List.of("ecclesiaflow-church-backend"));
        interceptor = new S2sAuthServerInterceptor(decoder, props, events, scopeRegistry, meters);
        Jwt jwt = jwt(Map.of("scope", "ef:s2s ef:members:read", "azp", "ecclesiaflow-church-backend"));
        when(decoder.decode("valid")).thenReturn(jwt);
        when(scopeRegistry.requiredScope("test.Service/Method"))
                .thenReturn(java.util.Optional.of("ef:members:read"));

        Metadata headers = new Metadata();
        headers.put(S2sAuthServerInterceptor.AUTHORIZATION_KEY, "Bearer valid");

        interceptor.interceptCall(call, headers, next);

        verify(next).startCall(eq(call), eq(headers));
        verify(call, never()).close(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsATokenWithNoAzpClaimOnceTheAllowListIsSet() {
        // An absent claim must not read as "allowed" — fail-closed, like everything
        // else on this plane.
        props.setAllowedAzp(List.of("ecclesiaflow-church-backend"));
        interceptor = new S2sAuthServerInterceptor(decoder, props, events, scopeRegistry, meters);
        when(decoder.decode("valid")).thenReturn(jwt(Map.of("scope", "ef:s2s ef:members:read")));

        Metadata headers = new Metadata();
        headers.put(S2sAuthServerInterceptor.AUTHORIZATION_KEY, "Bearer valid");

        interceptor.interceptCall(call, headers, next);

        verify(call).close(any(), any(Metadata.class));
        verify(next, never()).startCall(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void anEmptyAllowListLeavesTheCheckOff() {
        // The default: an existing deployment must be unaffected until the
        // property is set, otherwise shipping this cuts every module off at once.
        Jwt jwt = jwt(Map.of("scope", "ef:s2s ef:members:read", "azp", "anything-at-all"));
        when(decoder.decode("valid")).thenReturn(jwt);
        when(scopeRegistry.requiredScope("test.Service/Method"))
                .thenReturn(java.util.Optional.of("ef:members:read"));

        Metadata headers = new Metadata();
        headers.put(S2sAuthServerInterceptor.AUTHORIZATION_KEY, "Bearer valid");

        interceptor.interceptCall(call, headers, next);

        verify(next).startCall(eq(call), eq(headers));
    }

    @Test
    @SuppressWarnings("unchecked")
    void blankAllowListEntriesAreIgnoredRatherThanLockingEveryoneOut() {
        // A property set to an empty string binds to [""] — which would otherwise
        // arm the check with a list nothing can match.
        props.setAllowedAzp(List.of("", "   "));
        interceptor = new S2sAuthServerInterceptor(decoder, props, events, scopeRegistry, meters);
        Jwt jwt = jwt(Map.of("scope", "ef:s2s ef:members:read", "azp", "ecclesiaflow-church-backend"));
        when(decoder.decode("valid")).thenReturn(jwt);
        when(scopeRegistry.requiredScope("test.Service/Method"))
                .thenReturn(java.util.Optional.of("ef:members:read"));

        Metadata headers = new Metadata();
        headers.put(S2sAuthServerInterceptor.AUTHORIZATION_KEY, "Bearer valid");

        interceptor.interceptCall(call, headers, next);

        verify(next).startCall(eq(call), eq(headers));
    }

    // ========================================================================
    // The audit trail itself (F045)
    // ========================================================================

    @Test
    @SuppressWarnings("unchecked")
    void theAcceptedEventCarriesAMaskedSubjectNeverTheRawOne() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .issuer("http://kc")
                .audience(List.of("ecclesiaflow-internal"))
                .subject("7f3a91c2-0b44-4d1e-9c77-5f2b8e6a1d30")
                .claims(c -> c.put("scope", "ef:s2s ef:members:read"))
                .claims(c -> c.put("azp", "ecclesiaflow-church-backend"))
                .build();
        when(decoder.decode("valid")).thenReturn(jwt);
        when(scopeRegistry.requiredScope("test.Service/Method"))
                .thenReturn(java.util.Optional.of("ef:members:read"));

        Metadata headers = new Metadata();
        headers.put(S2sAuthServerInterceptor.AUTHORIZATION_KEY, "Bearer valid");

        interceptor.interceptCall(call, headers, next);

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(event.capture());
        S2sAuthEvents.InboundAccepted accepted = (S2sAuthEvents.InboundAccepted) event.getValue();
        assertThat(accepted.subject()).doesNotContain("7f3a91c2-0b44-4d1e-9c77-5f2b8e6a1d30");
        assertThat(accepted.subject()).startsWith("7f3a91c2");
        assertThat(accepted.azp()).isEqualTo("ecclesiaflow-church-backend");
        assertThat(accepted.methodScope()).isEqualTo("ef:members:read");
    }

    @Test
    @SuppressWarnings("unchecked")
    void refusalsAreCountedToo() {
        Metadata headers = new Metadata();

        interceptor.interceptCall(call, headers, next);

        assertThat(counted("missing_header")).isEqualTo(1d);
    }

    @Test
    @SuppressWarnings("unchecked")
    void countsAnAbsentAuthorizedPartyAsUnknownRatherThanThrowing() {
        // A null tag value throws inside Micrometer and would take down the RPC.
        Jwt jwt = jwt(Map.of("scope", "ef:other"));
        when(decoder.decode("valid")).thenReturn(jwt);

        Metadata headers = new Metadata();
        headers.put(S2sAuthServerInterceptor.AUTHORIZATION_KEY, "Bearer valid");

        interceptor.interceptCall(call, headers, next);

        assertThat(meters.find(S2sAuthServerInterceptor.METRIC).tag("azp", "unknown").counter())
                .isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void worksWithoutAMeterRegistry() {
        // the four-argument constructor is the one consumers without metrics use
        S2sAuthServerInterceptor noMeters =
                new S2sAuthServerInterceptor(decoder, props, events, scopeRegistry);
        Jwt jwt = jwt(Map.of("scope", "ef:s2s ef:members:read"));
        when(decoder.decode("valid")).thenReturn(jwt);
        when(scopeRegistry.requiredScope("test.Service/Method"))
                .thenReturn(java.util.Optional.of("ef:members:read"));

        Metadata headers = new Metadata();
        headers.put(S2sAuthServerInterceptor.AUTHORIZATION_KEY, "Bearer valid");

        noMeters.interceptCall(call, headers, next);

        verify(next).startCall(eq(call), eq(headers));
    }

    /**
     * Builds a decoded JWT as it would look <em>after</em> the decoder has already
     * accepted it. Real iss + aud claims are stamped so the fixtures mirror what an
     * internal service-account token actually carries; the interceptor itself trusts
     * the decoder, so iss/aud enforcement is covered by
     * {@code PlatformRpcJwtDecoderValidatorTest}, not here.
     */
    private static Jwt jwt(Map<String, Object> claims) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .issuer("http://kc")
                .audience(List.of("ecclesiaflow-internal"))
                .claims(c -> c.putAll(claims))
                .build();
    }
}
