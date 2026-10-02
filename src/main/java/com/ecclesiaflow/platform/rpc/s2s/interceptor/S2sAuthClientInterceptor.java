package com.ecclesiaflow.platform.rpc.s2s.interceptor;
import com.ecclesiaflow.platform.rpc.s2s.token.S2sTokenException;
import com.ecclesiaflow.platform.rpc.s2s.token.S2sTokenProvider;

import com.ecclesiaflow.platform.rpc.events.S2sAuthEvents;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.Deadline;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * gRPC client interceptor that attaches a Bearer JWT to every outgoing call.
 *
 * <p><strong>Single responsibility:</strong> request the token from the provider
 * and put it in the {@code authorization} metadata header. On failure, an event
 * is published (no logging here) and the call is aborted with
 * {@link Status#UNAVAILABLE} — or {@link Status#DEADLINE_EXCEEDED} once the call's
 * deadline has passed — so callers see a clear gRPC status instead of an exception
 * leak. A call never waits for a token past its own deadline, and a token the server
 * answers {@code UNAUTHENTICATED} to is dropped so the next call fetches a new one.</p>
 *
 * <p>Install once per {@link io.grpc.ManagedChannel} via
 * {@link io.grpc.ManagedChannelBuilder#intercept(ClientInterceptor...)}.</p>
 */
public class S2sAuthClientInterceptor implements ClientInterceptor {

    /** {@code authorization} metadata key. Lowercase per HTTP/2 convention. */
    static final Metadata.Key<String> AUTHORIZATION_KEY =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final S2sTokenProvider tokenProvider;
    private final ApplicationEventPublisher events;

    public S2sAuthClientInterceptor(S2sTokenProvider tokenProvider, ApplicationEventPublisher events) {
        this.tokenProvider = tokenProvider;
        this.events = events;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method,
            CallOptions callOptions,
            Channel next) {

        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                Deadline deadline = callOptions.getDeadline();
                String token;
                try {
                    token = deadline == null
                            ? tokenProvider.getToken()
                            : tokenProvider.getToken(remaining(deadline));
                } catch (S2sTokenException e) {
                    events.publishEvent(new S2sAuthEvents.OutboundTokenUnavailable(
                            method.getFullMethodName(), e.getMessage()));
                    Status status = deadline != null && deadline.isExpired()
                            ? Status.DEADLINE_EXCEEDED
                            : Status.UNAVAILABLE;
                    throw new StatusRuntimeException(status.withDescription("s2s token unavailable").withCause(e));
                }
                headers.put(AUTHORIZATION_KEY, "Bearer " + token);
                super.start(new DropTokenOnUnauthenticated<>(responseListener, tokenProvider), headers);
            }
        };
    }

    private static Duration remaining(Deadline deadline) {
        return Duration.ofNanos(Math.max(0, deadline.timeRemaining(TimeUnit.NANOSECONDS)));
    }

    private static final class DropTokenOnUnauthenticated<RespT>
            extends ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT> {

        private final S2sTokenProvider tokenProvider;

        DropTokenOnUnauthenticated(ClientCall.Listener<RespT> delegate, S2sTokenProvider tokenProvider) {
            super(delegate);
            this.tokenProvider = tokenProvider;
        }

        @Override
        public void onClose(Status status, Metadata trailers) {
            if (status.getCode() == Status.Code.UNAUTHENTICATED) {
                tokenProvider.invalidate();
            }
            super.onClose(status, trailers);
        }
    }
}
