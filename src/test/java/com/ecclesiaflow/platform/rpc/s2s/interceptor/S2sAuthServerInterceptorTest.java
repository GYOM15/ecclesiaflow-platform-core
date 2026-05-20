package com.ecclesiaflow.platform.rpc.s2s.interceptor;

import com.ecclesiaflow.platform.rpc.s2s.S2sProperties;

import com.ecclesiaflow.platform.rpc.events.S2sAuthEvents;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
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
    private S2sAuthServerInterceptor interceptor;

    @BeforeEach
    void setUp() {
        decoder = mock(JwtDecoder.class);
        events = mock(ApplicationEventPublisher.class);
        call = mock(ServerCall.class);
        next = mock(ServerCallHandler.class);
        methodDescriptor = mock(MethodDescriptor.class);
        when(call.getMethodDescriptor()).thenReturn(methodDescriptor);
        when(methodDescriptor.getFullMethodName()).thenReturn("test.Service/Method");

        props = new S2sProperties();
        props.setClientId("ecclesiaflow-backend");
        props.setClientSecret("x");
        props.setTokenUrl("http://kc/token");
        props.setJwksUri("http://kc/jwks");
        props.setIssuer("http://kc");
        props.setGenericScope("ef:s2s");

        interceptor = new S2sAuthServerInterceptor(decoder, props, events);
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
        Jwt jwt = jwt(Map.of("scope", "ef:s2s ef:email:send"));
        when(decoder.decode("valid")).thenReturn(jwt);

        Metadata headers = new Metadata();
        headers.put(S2sAuthServerInterceptor.AUTHORIZATION_KEY, "Bearer valid");

        interceptor.interceptCall(call, headers, next);

        verify(next).startCall(eq(call), eq(headers));
        verify(call, never()).close(any(), any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptsTokenWithRequiredScopeInScpClaimArray() {
        Jwt jwt = jwt(Map.of("scp", List.of("ef:s2s", "ef:email:send")));
        when(decoder.decode("valid")).thenReturn(jwt);

        Metadata headers = new Metadata();
        headers.put(S2sAuthServerInterceptor.AUTHORIZATION_KEY, "Bearer valid");

        interceptor.interceptCall(call, headers, next);

        verify(next).startCall(eq(call), eq(headers));
        verify(events, never()).publishEvent(any());
    }

    private static Jwt jwt(Map<String, Object> claims) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claims(c -> c.putAll(claims))
                .build();
    }
}
