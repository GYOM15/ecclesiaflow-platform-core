package com.ecclesiaflow.platform.rpc.s2s.interceptor;
import com.ecclesiaflow.platform.rpc.s2s.token.S2sToken;
import com.ecclesiaflow.platform.rpc.s2s.token.S2sTokenCache;
import com.ecclesiaflow.platform.rpc.s2s.token.S2sTokenClient;
import com.ecclesiaflow.platform.rpc.s2s.token.S2sTokenException;
import com.ecclesiaflow.platform.rpc.s2s.token.S2sTokenProvider;

import com.ecclesiaflow.platform.rpc.s2s.S2sProperties;

import com.ecclesiaflow.platform.rpc.events.S2sAuthEvents;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The interceptor's job is narrow and easily testable without standing up a real
 * gRPC server: we just verify that {@code start()} either populates the headers
 * with a Bearer token or fails fast with {@code UNAVAILABLE} (and publishes the
 * expected event).
 */
class S2sAuthClientInterceptorTest {

    private static final MethodDescriptor<Object, Object> METHOD = MethodDescriptor.<Object, Object>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("test.Service/Method")
            .setRequestMarshaller(new NoopMarshaller())
            .setResponseMarshaller(new NoopMarshaller())
            .build();

    private S2sTokenProvider provider;
    private ApplicationEventPublisher events;
    private Channel next;
    private ClientCall<Object, Object> delegate;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        provider = mock(S2sTokenProvider.class);
        events = mock(ApplicationEventPublisher.class);
        next = mock(Channel.class);
        delegate = mock(ClientCall.class);
        when(next.newCall(METHOD, CallOptions.DEFAULT)).thenReturn(delegate);
    }

    @Test
    void attachesBearerHeaderOnStart() {
        when(provider.getToken()).thenReturn("jwt-from-cache");

        ClientCall<Object, Object> intercepted = new S2sAuthClientInterceptor(provider, events)
                .interceptCall(METHOD, CallOptions.DEFAULT, next);

        Metadata headers = new Metadata();
        ClientCall.Listener<Object> listener = new ClientCall.Listener<>() {};
        intercepted.start(listener, headers);

        assertThat(headers.get(S2sAuthClientInterceptor.AUTHORIZATION_KEY))
                .isEqualTo("Bearer jwt-from-cache");
        verify(events, never()).publishEvent(any());
    }

    @Test
    void abortsCallAndPublishesEventWhenTokenFetchFails() {
        when(provider.getToken()).thenThrow(new S2sTokenException("keycloak down"));

        ClientCall<Object, Object> intercepted = new S2sAuthClientInterceptor(provider, events)
                .interceptCall(METHOD, CallOptions.DEFAULT, next);

        assertThatThrownBy(() -> intercepted.start(new ClientCall.Listener<>() {}, new Metadata()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(t -> {
                    Status status = ((StatusRuntimeException) t).getStatus();
                    assertThat(status.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
                    assertThat(status.getDescription()).isEqualTo("s2s token unavailable");
                });

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue())
                .isInstanceOf(S2sAuthEvents.OutboundTokenUnavailable.class)
                .satisfies(e -> {
                    S2sAuthEvents.OutboundTokenUnavailable evt = (S2sAuthEvents.OutboundTokenUnavailable) e;
                    assertThat(evt.fullMethodName()).isEqualTo("test.Service/Method");
                    assertThat(evt.reason()).isEqualTo("keycloak down");
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void aCallerIsNotHeldPastItsDeadlineByAHangingTokenEndpoint() {
        S2sProperties props = new S2sProperties();
        props.setClientId("ecclesiaflow-church-backend");
        props.setClientSecret("x");
        props.setTokenUrl("http://localhost:1/token");
        CountDownLatch keycloakAnswers = new CountDownLatch(1);
        S2sTokenClient hanging = new S2sTokenClient(props) {
            @Override
            public S2sToken fetchToken() {
                try {
                    keycloakAnswers.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new S2sToken("late", Instant.now().plusSeconds(300));
            }
        };
        S2sTokenProvider realProvider = new S2sTokenProvider(hanging, new S2sTokenCache(), props);
        CallOptions withDeadline = CallOptions.DEFAULT.withDeadlineAfter(200, TimeUnit.MILLISECONDS);
        when(next.newCall(METHOD, withDeadline)).thenReturn(delegate);
        ClientCall<Object, Object> intercepted = new S2sAuthClientInterceptor(realProvider, events)
                .interceptCall(METHOD, withDeadline, next);

        long started = System.nanoTime();
        Throwable thrown;
        try {
            thrown = catchThrowable(() -> intercepted.start(new ClientCall.Listener<>() {}, new Metadata()));
        } finally {
            keycloakAnswers.countDown();
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(elapsedMillis).isLessThan(1_500);
        assertThat(thrown).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) thrown).getStatus().getCode()).isEqualTo(Status.Code.DEADLINE_EXCEEDED);
        verify(delegate, never()).start(any(), any());
    }

    @Test
    void aCallWithADeadlineWaitsForTheTokenNoLongerThanTheTimeItHasLeft() {
        CallOptions withDeadline = CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS);
        when(next.newCall(METHOD, withDeadline)).thenReturn(delegate);
        when(provider.getToken(any(Duration.class))).thenReturn("jwt");

        new S2sAuthClientInterceptor(provider, events)
                .interceptCall(METHOD, withDeadline, next)
                .start(new ClientCall.Listener<>() {}, new Metadata());

        ArgumentCaptor<Duration> bound = ArgumentCaptor.forClass(Duration.class);
        verify(provider).getToken(bound.capture());
        assertThat(bound.getValue()).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(5));
        verify(provider, never()).getToken();
    }

    @Test
    void aTokenFailureBeforeTheDeadlineIsUnavailable() {
        CallOptions withDeadline = CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS);
        when(next.newCall(METHOD, withDeadline)).thenReturn(delegate);
        when(provider.getToken(any(Duration.class))).thenThrow(new S2sTokenException("keycloak down"));
        ClientCall<Object, Object> intercepted = new S2sAuthClientInterceptor(provider, events)
                .interceptCall(METHOD, withDeadline, next);

        assertThatThrownBy(() -> intercepted.start(new ClientCall.Listener<>() {}, new Metadata()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(t -> assertThat(((StatusRuntimeException) t).getStatus().getCode())
                        .isEqualTo(Status.Code.UNAVAILABLE));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aTokenTheServerRejectsIsDroppedForTheNextCall() {
        when(provider.getToken()).thenReturn("revoked-jwt");
        ClientCall<Object, Object> intercepted = new S2sAuthClientInterceptor(provider, events)
                .interceptCall(METHOD, CallOptions.DEFAULT, next);

        intercepted.start(new ClientCall.Listener<>() {}, new Metadata());
        ArgumentCaptor<ClientCall.Listener<Object>> forwarded = ArgumentCaptor.forClass(ClientCall.Listener.class);
        verify(delegate).start(forwarded.capture(), any(Metadata.class));
        forwarded.getValue().onClose(Status.UNAUTHENTICATED, new Metadata());

        verify(provider).invalidate();
    }

    @Test
    @SuppressWarnings("unchecked")
    void otherOutcomesKeepTheToken() {
        when(provider.getToken()).thenReturn("jwt");
        ClientCall<Object, Object> intercepted = new S2sAuthClientInterceptor(provider, events)
                .interceptCall(METHOD, CallOptions.DEFAULT, next);
        ClientCall.Listener<Object> caller = mock(ClientCall.Listener.class);

        intercepted.start(caller, new Metadata());
        ArgumentCaptor<ClientCall.Listener<Object>> forwarded = ArgumentCaptor.forClass(ClientCall.Listener.class);
        verify(delegate).start(forwarded.capture(), any(Metadata.class));
        forwarded.getValue().onClose(Status.PERMISSION_DENIED, new Metadata());

        verify(provider, never()).invalidate();
        verify(caller).onClose(eq(Status.PERMISSION_DENIED), any(Metadata.class));
    }

    private static final class NoopMarshaller implements MethodDescriptor.Marshaller<Object> {
        @Override
        public java.io.InputStream stream(Object value) {
            return new java.io.ByteArrayInputStream(new byte[0]);
        }

        @Override
        public Object parse(java.io.InputStream stream) {
            return new Object();
        }
    }
}
