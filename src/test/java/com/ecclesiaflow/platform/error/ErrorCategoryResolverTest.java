package com.ecclesiaflow.platform.error;

import com.ecclesiaflow.platform.ratelimit.RateLimitExceededException;
import com.ecclesiaflow.platform.rpc.s2s.token.S2sTokenException;
import io.grpc.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorCategoryResolverTest {

    private final ErrorCategoryResolver defaults = new ErrorCategoryResolver(List.of());

    /** Stand-in for an adapter exception that wraps whatever the client library threw. */
    static class AdapterException extends RuntimeException {
        AdapterException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static class MemberNotFoundException extends RuntimeException {
    }

    static class ArchivedMemberNotFoundException extends MemberNotFoundException {
    }

    static class AccountAlreadyActivatedException extends RuntimeException {
    }

    @Nested
    @DisplayName("a dependency outage")
    class DependencyOutage {

        @Test
        @DisplayName("is UNAVAILABLE even when an adapter wraps it, never a caller error")
        void wrappedConnectFailureIsUnavailable() {
            Throwable keycloakDown = new AdapterException("Failed to look up realm role: CHURCH_ADMIN",
                    new ConnectException("Connection refused"));

            assertThat(defaults.resolve(keycloakDown)).isEqualTo(ErrorCategory.UNAVAILABLE);
        }

        @Test
        @DisplayName("is recognised from JDK network and timeout failures")
        void jdkFailures() {
            assertThat(defaults.resolve(new SocketTimeoutException("read timed out"))).isEqualTo(ErrorCategory.UNAVAILABLE);
            assertThat(defaults.resolve(new HttpConnectTimeoutException("connect timed out"))).isEqualTo(ErrorCategory.UNAVAILABLE);
            assertThat(defaults.resolve(new TimeoutException())).isEqualTo(ErrorCategory.UNAVAILABLE);
            assertThat(defaults.resolve(new UncheckedIOException(new ConnectException("refused")))).isEqualTo(ErrorCategory.UNAVAILABLE);
        }

        @Test
        @DisplayName("is recognised from Spring data, transaction and HTTP client failures")
        void springFailures() {
            assertThat(defaults.resolve(new RedisConnectionFailureException("down"))).isEqualTo(ErrorCategory.UNAVAILABLE);
            assertThat(defaults.resolve(new CannotCreateTransactionException("db down"))).isEqualTo(ErrorCategory.UNAVAILABLE);
            assertThat(defaults.resolve(new ResourceAccessException("I/O error"))).isEqualTo(ErrorCategory.UNAVAILABLE);
        }

        @Test
        @DisplayName("is recognised when the s2s token cannot be obtained")
        void s2sTokenFailure() {
            assertThat(defaults.resolve(new S2sTokenException("token endpoint unreachable"))).isEqualTo(ErrorCategory.UNAVAILABLE);
        }

        @Test
        @DisplayName("is recognised from a downstream gRPC call that was unavailable or timed out")
        void downstreamGrpc() {
            assertThat(defaults.resolve(new AdapterException("call failed", Status.UNAVAILABLE.asRuntimeException())))
                    .isEqualTo(ErrorCategory.UNAVAILABLE);
            assertThat(defaults.resolve(Status.DEADLINE_EXCEEDED.asException())).isEqualTo(ErrorCategory.UNAVAILABLE);
            assertThat(defaults.resolve(Status.NOT_FOUND.asRuntimeException())).isEqualTo(ErrorCategory.INTERNAL);
        }

        @Test
        @DisplayName("a plain I/O exception without a network cause is not assumed to be an outage")
        void plainIoIsInternal() {
            assertThat(defaults.resolve(new UncheckedIOException(new IOException("disk full")))).isEqualTo(ErrorCategory.INTERNAL);
        }
    }

    @Nested
    @DisplayName("built-in rules")
    class BuiltIns {

        @Test
        @DisplayName("IllegalArgumentException is a caller error, as every module already maps it")
        void illegalArgument() {
            assertThat(defaults.resolve(new IllegalArgumentException("bad uuid"))).isEqualTo(ErrorCategory.INVALID_ARGUMENT);
        }

        @Test
        @DisplayName("IllegalStateException is not assumed to be a caller error")
        void illegalStateIsInternal() {
            assertThat(defaults.resolve(new IllegalStateException("not initialised"))).isEqualTo(ErrorCategory.INTERNAL);
        }

        @Test
        @DisplayName("a rate-limit refusal is RESOURCE_EXHAUSTED")
        void rateLimit() {
            assertThat(defaults.resolve(new RateLimitExceededException("import", 30))).isEqualTo(ErrorCategory.RESOURCE_EXHAUSTED);
        }

        @Test
        @DisplayName("a unique or integrity violation is a conflict, a lost optimistic lock is ABORTED")
        void dataConflicts() {
            assertThat(defaults.resolve(new DuplicateKeyException("dup"))).isEqualTo(ErrorCategory.ALREADY_EXISTS);
            assertThat(defaults.resolve(new DataIntegrityViolationException("uk_email"))).isEqualTo(ErrorCategory.ALREADY_EXISTS);
            assertThat(defaults.resolve(new OptimisticLockingFailureException("stale"))).isEqualTo(ErrorCategory.ABORTED);
        }

        @Test
        @DisplayName("Spring Security refusals keep their meaning")
        void security() {
            assertThat(defaults.resolve(new AccessDeniedException("no"))).isEqualTo(ErrorCategory.PERMISSION_DENIED);
            assertThat(defaults.resolve(new BadCredentialsException("no"))).isEqualTo(ErrorCategory.UNAUTHENTICATED);
        }

        @Test
        @DisplayName("anything else is INTERNAL")
        void unknown() {
            assertThat(defaults.resolve(new RuntimeException("boom"))).isEqualTo(ErrorCategory.INTERNAL);
            assertThat(defaults.resolve(new NullPointerException())).isEqualTo(ErrorCategory.INTERNAL);
        }

        @Test
        @DisplayName("the cause chain is read to a bounded depth")
        void boundedDepth() {
            Throwable deep = new ConnectException("refused");
            for (int i = 0; i < 20; i++) {
                deep = new RuntimeException("layer " + i, deep);
            }

            assertThat(defaults.resolve(deep)).isEqualTo(ErrorCategory.INTERNAL);
        }

        @Test
        @DisplayName("a cause cycle does not hang the resolver")
        void causeCycle() {
            RuntimeException a = new RuntimeException("a");
            RuntimeException b = new RuntimeException("b", a);
            a.initCause(b);

            assertThat(defaults.resolve(a)).isEqualTo(ErrorCategory.INTERNAL);
        }
    }

    @Nested
    @DisplayName("module classifiers")
    class ModuleClassifiers {

        private final ExceptionClassifier members = ExceptionClassifier.byType(Map.of(
                MemberNotFoundException.class, ErrorCategory.NOT_FOUND,
                AccountAlreadyActivatedException.class, ErrorCategory.FAILED_PRECONDITION));

        @Test
        @DisplayName("map the module's own exceptions, subclasses included")
        void mapsModuleExceptions() {
            ErrorCategoryResolver resolver = new ErrorCategoryResolver(List.of(members));

            assertThat(resolver.resolve(new MemberNotFoundException())).isEqualTo(ErrorCategory.NOT_FOUND);
            assertThat(resolver.resolve(new ArchivedMemberNotFoundException())).isEqualTo(ErrorCategory.NOT_FOUND);
            assertThat(resolver.resolve(new AccountAlreadyActivatedException())).isEqualTo(ErrorCategory.FAILED_PRECONDITION);
        }

        @Test
        @DisplayName("the most specific mapped type wins")
        void mostSpecificWins() {
            ExceptionClassifier classifier = ExceptionClassifier.byType(Map.of(
                    MemberNotFoundException.class, ErrorCategory.NOT_FOUND,
                    ArchivedMemberNotFoundException.class, ErrorCategory.FAILED_PRECONDITION));

            assertThat(classifier.classify(new ArchivedMemberNotFoundException())).contains(ErrorCategory.FAILED_PRECONDITION);
            assertThat(classifier.classify(new IllegalStateException())).isEmpty();
        }

        @Test
        @DisplayName("are consulted before the built-in rules, so a module can override them")
        void overrideBuiltIns() {
            ExceptionClassifier strict = error -> error instanceof IllegalStateException
                    ? Optional.of(ErrorCategory.FAILED_PRECONDITION) : Optional.empty();
            ErrorCategoryResolver resolver = new ErrorCategoryResolver(List.of(strict));

            assertThat(resolver.resolve(new IllegalStateException("already active"))).isEqualTo(ErrorCategory.FAILED_PRECONDITION);
        }

        @Test
        @DisplayName("also see a mapped exception wrapped by another one")
        void wrapped() {
            ErrorCategoryResolver resolver = new ErrorCategoryResolver(List.of(members));

            assertThat(resolver.resolve(new AdapterException("wrapped", new MemberNotFoundException())))
                    .isEqualTo(ErrorCategory.NOT_FOUND);
        }

        @Test
        @DisplayName("are asked in order, the first answer wins")
        void firstAnswerWins() {
            ExceptionClassifier first = error -> Optional.of(ErrorCategory.NOT_FOUND);
            ExceptionClassifier second = error -> Optional.of(ErrorCategory.INTERNAL);

            assertThat(new ErrorCategoryResolver(List.of(first, second)).resolve(new RuntimeException()))
                    .isEqualTo(ErrorCategory.NOT_FOUND);
        }
    }

    @Test
    @DisplayName("every category carries the HTTP status the REST contract uses")
    void httpStatuses() {
        assertThat(ErrorCategory.INVALID_ARGUMENT.httpStatus()).isEqualTo(400);
        assertThat(ErrorCategory.UNAUTHENTICATED.httpStatus()).isEqualTo(401);
        assertThat(ErrorCategory.PERMISSION_DENIED.httpStatus()).isEqualTo(403);
        assertThat(ErrorCategory.NOT_FOUND.httpStatus()).isEqualTo(404);
        assertThat(ErrorCategory.ALREADY_EXISTS.httpStatus()).isEqualTo(409);
        assertThat(ErrorCategory.FAILED_PRECONDITION.httpStatus()).isEqualTo(409);
        assertThat(ErrorCategory.ABORTED.httpStatus()).isEqualTo(409);
        assertThat(ErrorCategory.RESOURCE_EXHAUSTED.httpStatus()).isEqualTo(429);
        assertThat(ErrorCategory.UNAVAILABLE.httpStatus()).isEqualTo(503);
        assertThat(ErrorCategory.INTERNAL.httpStatus()).isEqualTo(500);
    }
}
