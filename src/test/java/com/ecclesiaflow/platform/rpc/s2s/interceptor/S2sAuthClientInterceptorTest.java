package com.ecclesiaflow.platform.rpc.s2s.interceptor;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
