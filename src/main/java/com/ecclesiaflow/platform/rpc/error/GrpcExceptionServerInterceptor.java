package com.ecclesiaflow.platform.rpc.error;

import com.ecclesiaflow.platform.rpc.events.RpcCallFailed;
import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Lets a service throw instead of answering gRPC's opaque UNKNOWN, and reports every non-OK close
 * once, explicit ones included. Register it last on the ServerBuilder so it wraps the others.
 */
public class GrpcExceptionServerInterceptor implements ServerInterceptor {

    private final GrpcStatusMapper mapper;
    private final ApplicationEventPublisher events;

    public GrpcExceptionServerInterceptor(GrpcStatusMapper mapper, ApplicationEventPublisher events) {
        this.mapper = mapper;
        this.events = events;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                                                                 Metadata headers,
                                                                 ServerCallHandler<ReqT, RespT> next) {
        FailureReportingCall<ReqT, RespT> reporting = new FailureReportingCall<>(call);
        try {
            return new FailureCatchingListener<>(next.startCall(reporting, headers), reporting);
        } catch (Exception e) {
            // Streaming handlers invoke the service method inside startCall.
            reporting.fail(e);
            return new ServerCall.Listener<>() {
            };
        }
    }

    private final class FailureReportingCall<ReqT, RespT>
            extends ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT> {

        private volatile boolean closed;

        FailureReportingCall(ServerCall<ReqT, RespT> delegate) {
            super(delegate);
        }

        @Override
        public void close(Status status, Metadata trailers) {
            Status effective = isRawException(status) ? mapper.toStatus(status.getCause()) : status;
            closed = true;
            super.close(effective, trailers);
            if (!effective.isOk()) {
                report(effective.getCode(), effective.getCause());
            }
        }

        void fail(Throwable error) {
            Status status = mapper.toStatus(error);
            if (closed) {
                report(status.getCode(), error);
                return;
            }
            close(status.getCause() == null ? status.withCause(error) : status, new Metadata());
        }

        private void report(Status.Code code, Throwable error) {
            events.publishEvent(new RpcCallFailed(getMethodDescriptor().getFullMethodName(), code, error));
        }
    }

    // gRPC turns a raw exception passed to onError into UNKNOWN with that exception as cause.
    private static boolean isRawException(Status status) {
        Throwable cause = status.getCause();
        return status.getCode() == Status.Code.UNKNOWN && cause != null
                && !(cause instanceof StatusRuntimeException) && !(cause instanceof StatusException);
    }

    private static final class FailureCatchingListener<ReqT>
            extends ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT> {

        private final FailureReportingCall<ReqT, ?> call;

        FailureCatchingListener(ServerCall.Listener<ReqT> delegate, FailureReportingCall<ReqT, ?> call) {
            super(delegate);
            this.call = call;
        }

        @Override
        public void onMessage(ReqT message) {
            guard(() -> super.onMessage(message));
        }

        @Override
        public void onHalfClose() {
            guard(super::onHalfClose);
        }

        @Override
        public void onReady() {
            guard(super::onReady);
        }

        @Override
        public void onCancel() {
            guard(super::onCancel);
        }

        @Override
        public void onComplete() {
            guard(super::onComplete);
        }

        private void guard(Runnable step) {
            try {
                step.run();
            } catch (Exception e) {
                call.fail(e);
            }
        }
    }
}
