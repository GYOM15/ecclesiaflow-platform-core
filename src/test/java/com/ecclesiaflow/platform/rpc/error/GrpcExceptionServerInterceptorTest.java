package com.ecclesiaflow.platform.rpc.error;

import com.ecclesiaflow.platform.error.ErrorCategory;
import com.ecclesiaflow.platform.error.ErrorCategoryResolver;
import com.ecclesiaflow.platform.error.ExceptionClassifier;
import com.ecclesiaflow.platform.rpc.events.RpcCallFailed;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs a real in-process gRPC server, built the way the services build theirs
 * ({@code ServerBuilder.intercept}), so what is asserted is what a calling module receives.
 */
class GrpcExceptionServerInterceptorTest {

    private static final MethodDescriptor.Marshaller<String> TEXT = new MethodDescriptor.Marshaller<>() {
        @Override
        public InputStream stream(String value) {
            return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String parse(InputStream stream) {
            try {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    };

    private static final MethodDescriptor<String, String> UNARY = MethodDescriptor.<String, String>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName(MethodDescriptor.generateFullMethodName("test.Members", "ProvisionMember"))
            .setRequestMarshaller(TEXT)
            .setResponseMarshaller(TEXT)
            .build();

    private static final MethodDescriptor<String, String> CLIENT_STREAMING = MethodDescriptor.<String, String>newBuilder()
            .setType(MethodDescriptor.MethodType.CLIENT_STREAMING)
            .setFullMethodName(MethodDescriptor.generateFullMethodName("test.Members", "ImportMembers"))
            .setRequestMarshaller(TEXT)
            .setResponseMarshaller(TEXT)
            .build();

    static class MemberNotFoundException extends RuntimeException {
        MemberNotFoundException(String message) {
            super(message);
        }
    }

    private final List<Object> events = new CopyOnWriteArrayList<>();
    private Server server;
    private ManagedChannel channel;

    @AfterEach
    void shutDown() throws InterruptedException {
        if (channel != null) {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
        if (server != null) {
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private void serve(BiConsumer<String, StreamObserver<String>> unary) throws IOException {
        serve(unary, List.of(), responses -> new StreamObserver<>() {
            @Override
            public void onNext(String value) {
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onCompleted() {
                responses.onNext("done");
                responses.onCompleted();
            }
        });
    }

    private void serve(BiConsumer<String, StreamObserver<String>> unary,
                       List<ExceptionClassifier> classifiers,
                       Function<StreamObserver<String>, StreamObserver<String>> clientStreaming) throws IOException {
        ServerServiceDefinition service = ServerServiceDefinition.builder("test.Members")
                .addMethod(UNARY, ServerCalls.asyncUnaryCall(unary::accept))
                .addMethod(CLIENT_STREAMING, ServerCalls.asyncClientStreamingCall(clientStreaming::apply))
                .build();
        GrpcExceptionServerInterceptor interceptor = new GrpcExceptionServerInterceptor(
                new GrpcStatusMapper(new ErrorCategoryResolver(classifiers)), events::add);
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(service)
                .intercept(interceptor)
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    private String call() {
        return ClientCalls.blockingUnaryCall(channel, UNARY, CallOptions.DEFAULT, "request");
    }

    private Status statusOfCall() {
        try {
            call();
        } catch (StatusRuntimeException e) {
            return e.getStatus();
        }
        throw new AssertionError("the call succeeded");
    }

    private List<RpcCallFailed> failures() {
        return events.stream().filter(RpcCallFailed.class::isInstance).map(RpcCallFailed.class::cast).toList();
    }

    @Nested
    @DisplayName("a service that throws")
    class Throwing {

        @Test
        @DisplayName("a dependency outage reaches the caller as UNAVAILABLE, not NOT_FOUND or UNKNOWN")
        void outageIsUnavailable() throws IOException {
            serve((request, responses) -> {
                throw new IllegalStateException("Failed to look up realm role: CHURCH_ADMIN",
                        new ConnectException("Connection refused"));
            });

            Status status = statusOfCall();

            assertThat(status.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
            assertThat(failures()).singleElement()
                    .satisfies(failed -> assertThat(failed.code()).isEqualTo(Status.Code.UNAVAILABLE));
        }

        @Test
        @DisplayName("an unexpected failure is INTERNAL, opaque to the caller, and reported exactly once")
        void unexpectedIsInternal() throws IOException {
            RuntimeException boom = new RuntimeException("duplicate key value violates unique constraint uk_member_email");
            serve((request, responses) -> {
                throw boom;
            });

            Status status = statusOfCall();

            assertThat(status.getCode()).isEqualTo(Status.Code.INTERNAL);
            assertThat(status.getDescription()).isEqualTo("Internal error");
            assertThat(failures()).singleElement().satisfies(failed -> {
                assertThat(failed.fullMethodName()).isEqualTo("test.Members/ProvisionMember");
                assertThat(failed.code()).isEqualTo(Status.Code.INTERNAL);
                assertThat(failed.error()).isSameAs(boom);
            });
        }

        @Test
        @DisplayName("a caller error is INVALID_ARGUMENT with its message")
        void callerError() throws IOException {
            serve((request, responses) -> {
                throw new IllegalArgumentException("member_id must be a UUID");
            });

            Status status = statusOfCall();

            assertThat(status.getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
            assertThat(status.getDescription()).isEqualTo("member_id must be a UUID");
        }

        @Test
        @DisplayName("a module classifier gives the module's own exception its meaning")
        void moduleClassifier() throws IOException {
            serve((request, responses) -> {
                throw new MemberNotFoundException("no member for this subject");
            }, List.of(ExceptionClassifier.byType(Map.of(MemberNotFoundException.class, ErrorCategory.NOT_FOUND))),
                    responses -> null);

            assertThat(statusOfCall().getCode()).isEqualTo(Status.Code.NOT_FOUND);
        }

        @Test
        @DisplayName("a status raised on purpose is kept as is")
        void deliberateStatus() throws IOException {
            serve((request, responses) -> {
                throw Status.FAILED_PRECONDITION.withDescription("account already activated").asRuntimeException();
            });

            Status status = statusOfCall();

            assertThat(status.getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
            assertThat(status.getDescription()).isEqualTo("account already activated");
        }

        @Test
        @DisplayName("a failure after the response was sent is still reported, without a second close")
        void failureAfterCompletion() throws IOException {
            RuntimeException late = new RuntimeException("audit write failed");
            serve((request, responses) -> {
                responses.onNext("ok");
                responses.onCompleted();
                throw late;
            });

            assertThat(call()).isEqualTo("ok");
            assertThat(failures()).singleElement()
                    .satisfies(failed -> assertThat(failed.error()).isSameAs(late));
        }

        @Test
        @DisplayName("a streaming handler that throws while starting is mapped too")
        void streamingStart() throws Exception {
            serve((request, responses) -> responses.onCompleted(), List.of(), responses -> {
                throw new IllegalArgumentException("import refused");
            });

            Status status = streamAndAwait(requests -> requests.onCompleted());

            assertThat(status.getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        }

        @Test
        @DisplayName("a streaming handler that throws on a message is mapped too")
        void streamingMessage() throws Exception {
            serve((request, responses) -> responses.onCompleted(), List.of(), responses -> new StreamObserver<>() {
                @Override
                public void onNext(String value) {
                    throw new IllegalArgumentException("row 3 has no email");
                }

                @Override
                public void onError(Throwable t) {
                }

                @Override
                public void onCompleted() {
                    responses.onCompleted();
                }
            });

            Status status = streamAndAwait(requests -> {
                requests.onNext("row");
                requests.onCompleted();
            });

            assertThat(status.getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
            assertThat(failures()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("a service that reports through onError")
    class ReportingThroughObserver {

        @Test
        @DisplayName("a raw exception becomes a mapped status instead of UNKNOWN")
        void rawExceptionIsMapped() throws IOException {
            serve((request, responses) -> responses.onError(new RuntimeException("smtp relay down")));

            Status status = statusOfCall();

            assertThat(status.getCode()).isEqualTo(Status.Code.INTERNAL);
            assertThat(failures()).hasSize(1);
        }

        @Test
        @DisplayName("an explicit UNKNOWN, bare or wrapping another status, is left as the service chose")
        void explicitUnknownIsKept() throws IOException {
            serve((request, responses) -> {
                switch (request) {
                    case "bare" -> responses.onError(Status.UNKNOWN.withDescription("bare").asRuntimeException());
                    case "runtime" -> responses.onError(Status.UNKNOWN.withDescription("runtime")
                            .withCause(Status.NOT_FOUND.asRuntimeException()).asRuntimeException());
                    default -> responses.onError(Status.UNKNOWN.withDescription("checked")
                            .withCause(Status.NOT_FOUND.asException()).asRuntimeException());
                }
            });

            for (String variant : List.of("bare", "runtime", "checked")) {
                assertThatThrownBy(() -> ClientCalls.blockingUnaryCall(channel, UNARY, CallOptions.DEFAULT, variant))
                        .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                            assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNKNOWN);
                            assertThat(e.getStatus().getDescription()).isEqualTo(variant);
                        });
            }
            assertThat(failures()).hasSize(3);
        }

        @Test
        @DisplayName("an explicit INTERNAL keeps its status and is reported, so the cause is no longer lost")
        void explicitInternalIsReported() throws IOException {
            RuntimeException cause = new RuntimeException("keycloak 500");
            serve((request, responses) -> responses.onError(
                    Status.INTERNAL.withDescription("Failed to provision member").withCause(cause).asRuntimeException()));

            Status status = statusOfCall();

            assertThat(status.getCode()).isEqualTo(Status.Code.INTERNAL);
            assertThat(status.getDescription()).isEqualTo("Failed to provision member");
            assertThat(failures()).singleElement()
                    .satisfies(failed -> assertThat(failed.error()).isSameAs(cause));
        }
    }

    @Test
    @DisplayName("without the interceptor the same outage reaches the caller as UNKNOWN and nothing is reported")
    void baselineWithoutInterceptor() throws IOException {
        ServerServiceDefinition service = ServerServiceDefinition.builder("test.Members")
                .addMethod(UNARY, ServerCalls.asyncUnaryCall((String request, StreamObserver<String> responses) -> {
                    throw new IllegalStateException("x", new ConnectException("Connection refused"));
                }))
                .build();
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor().addService(service).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();

        assertThat(statusOfCall().getCode()).isEqualTo(Status.Code.UNKNOWN);
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("a handler that throws while the client cancels is still reported")
    void failureOnCancel() throws Exception {
        CompletableFuture<Void> cancelled = new CompletableFuture<>();
        serve((request, responses) -> responses.onCompleted(), List.of(), responses -> new StreamObserver<>() {
            @Override
            public void onNext(String value) {
            }

            @Override
            public void onError(Throwable t) {
                cancelled.complete(null);
                throw new IllegalStateException("cleanup after cancel failed");
            }

            @Override
            public void onCompleted() {
                responses.onCompleted();
            }
        });

        StreamObserver<String> requests = ClientCalls.asyncClientStreamingCall(
                channel.newCall(CLIENT_STREAMING, CallOptions.DEFAULT), new StreamObserver<>() {
                    @Override
                    public void onNext(String value) {
                    }

                    @Override
                    public void onError(Throwable t) {
                    }

                    @Override
                    public void onCompleted() {
                    }
                });
        requests.onError(new RuntimeException("client gave up"));
        cancelled.get(5, TimeUnit.SECONDS);

        assertThat(failures()).anySatisfy(failed ->
                assertThat(failed.error()).hasMessage("cleanup after cancel failed"));
    }

    @Test
    @DisplayName("a successful call reports nothing")
    void successIsSilent() throws IOException {
        serve((request, responses) -> {
            responses.onNext("ok");
            responses.onCompleted();
        });

        assertThat(call()).isEqualTo("ok");
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("a failure is reported once even when the exception carries no message")
    void noMessage() throws IOException {
        serve((request, responses) -> {
            throw new NullPointerException();
        });

        assertThatThrownBy(GrpcExceptionServerInterceptorTest.this::call)
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
                .isEqualTo(Status.Code.INTERNAL);
        assertThat(failures()).hasSize(1);
    }

    private Status streamAndAwait(java.util.function.Consumer<StreamObserver<String>> send) throws Exception {
        CompletableFuture<Status> outcome = new CompletableFuture<>();
        StreamObserver<String> requests = ClientCalls.asyncClientStreamingCall(
                channel.newCall(CLIENT_STREAMING, CallOptions.DEFAULT), new StreamObserver<>() {
                    @Override
                    public void onNext(String value) {
                    }

                    @Override
                    public void onError(Throwable t) {
                        outcome.complete(Status.fromThrowable(t));
                    }

                    @Override
                    public void onCompleted() {
                        outcome.complete(Status.OK);
                    }
                });
        send.accept(requests);
        return outcome.get(5, TimeUnit.SECONDS);
    }
}
