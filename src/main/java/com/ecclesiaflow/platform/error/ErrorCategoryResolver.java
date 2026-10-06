package com.ecclesiaflow.platform.error;

import com.ecclesiaflow.platform.ratelimit.RateLimitExceededException;
import com.ecclesiaflow.platform.rpc.s2s.token.S2sTokenException;
import com.ecclesiaflow.platform.upload.ImageDecodeCapacityExceededException;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * An outage anywhere in the cause chain wins over the generic rules: reported as a caller error,
 * it tells the caller not to retry, which is how a Keycloak blip became a permanent "role not found".
 * A saturated local resource is read the same way: the request was never judged, so a retry is right.
 */
public final class ErrorCategoryResolver {

    private static final int MAX_CAUSE_DEPTH = 16;

    // Matched by name so the library does not drag these clients onto every service.
    private static final Set<String> DEPENDENCY_FAILURES = Set.of(
            "java.net.ConnectException",
            "java.net.NoRouteToHostException",
            "java.net.UnknownHostException",
            "java.net.SocketTimeoutException",
            "java.net.http.HttpTimeoutException",
            "java.util.concurrent.TimeoutException",
            "org.springframework.web.client.ResourceAccessException",
            "org.springframework.dao.DataAccessResourceFailureException",
            "org.springframework.dao.TransientDataAccessResourceException",
            "org.springframework.dao.QueryTimeoutException",
            "org.springframework.transaction.CannotCreateTransactionException",
            "org.springframework.amqp.AmqpConnectException",
            "feign.RetryableException",
            "feign.FeignException$FeignServerException",
            "io.github.resilience4j.circuitbreaker.CallNotPermittedException");

    // Only a unique index makes an integrity violation a lost race. A foreign key or a NOT NULL
    // column refused is a bug in the write, and must stay visible as one.
    private static final String DUPLICATE_KEY = "org.springframework.dao.DuplicateKeyException";
    private static final String DATA_INTEGRITY_VIOLATION = "org.springframework.dao.DataIntegrityViolationException";
    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";

    private static final Map<String, ErrorCategory> CONCURRENCY_FAILURES = Map.of(
            "org.springframework.dao.ConcurrencyFailureException", ErrorCategory.ABORTED,
            "jakarta.persistence.OptimisticLockException", ErrorCategory.ABORTED);

    private static final Map<String, ErrorCategory> SECURITY_REFUSALS = Map.of(
            "org.springframework.security.access.AccessDeniedException", ErrorCategory.PERMISSION_DENIED,
            "org.springframework.security.core.AuthenticationException", ErrorCategory.UNAUTHENTICATED);

    private final List<ExceptionClassifier> classifiers;

    public ErrorCategoryResolver(List<ExceptionClassifier> classifiers) {
        this.classifiers = List.copyOf(classifiers);
    }

    public ErrorCategory resolve(Throwable error) {
        List<Throwable> chain = causeChain(error);
        for (Throwable link : chain) {
            for (ExceptionClassifier classifier : classifiers) {
                Optional<ErrorCategory> category = classifier.classify(link);
                if (category.isPresent()) {
                    return category.get();
                }
            }
        }
        if (chain.stream().anyMatch(link -> isDependencyFailure(link) || isSaturation(link))) {
            return ErrorCategory.UNAVAILABLE;
        }
        if (error instanceof IllegalArgumentException) {
            return ErrorCategory.INVALID_ARGUMENT;
        }
        if (error instanceof RateLimitExceededException) {
            return ErrorCategory.RESOURCE_EXHAUSTED;
        }
        for (int i = 0; i < chain.size(); i++) {
            Throwable link = chain.get(i);
            List<Throwable> causes = chain.subList(i, chain.size());
            Optional<ErrorCategory> category = integrityViolation(link, causes)
                    .or(() -> byTypeName(link, CONCURRENCY_FAILURES))
                    .or(() -> byTypeName(link, SECURITY_REFUSALS));
            if (category.isPresent()) {
                return category.get();
            }
        }
        return ErrorCategory.INTERNAL;
    }

    private static Optional<ErrorCategory> integrityViolation(Throwable link, List<Throwable> causes) {
        if (isA(link, DUPLICATE_KEY)) {
            return Optional.of(ErrorCategory.ALREADY_EXISTS);
        }
        if (!isA(link, DATA_INTEGRITY_VIOLATION)) {
            return Optional.empty();
        }
        boolean unique = causes.stream().anyMatch(cause -> cause instanceof SQLException sql
                && UNIQUE_VIOLATION_SQL_STATE.equals(sql.getSQLState()));
        return Optional.of(unique ? ErrorCategory.ALREADY_EXISTS : ErrorCategory.INTERNAL);
    }

    private static boolean isA(Throwable error, String typeName) {
        for (Class<?> type = error.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().equals(typeName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDependencyFailure(Throwable error) {
        if (error instanceof S2sTokenException) {
            return true;
        }
        Status.Code downstream = error instanceof StatusRuntimeException sre ? sre.getStatus().getCode()
                : error instanceof StatusException se ? se.getStatus().getCode() : null;
        if (downstream != null) {
            return downstream == Status.Code.UNAVAILABLE || downstream == Status.Code.DEADLINE_EXCEEDED;
        }
        for (Class<?> type = error.getClass(); type != null; type = type.getSuperclass()) {
            if (DEPENDENCY_FAILURES.contains(type.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSaturation(Throwable error) {
        return error instanceof ImageDecodeCapacityExceededException;
    }

    private static Optional<ErrorCategory> byTypeName(Throwable error, Map<String, ErrorCategory> categories) {
        for (Class<?> type = error.getClass(); type != null; type = type.getSuperclass()) {
            ErrorCategory category = categories.get(type.getName());
            if (category != null) {
                return Optional.of(category);
            }
        }
        return Optional.empty();
    }

    private static List<Throwable> causeChain(Throwable error) {
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable link = error; link != null && chain.size() < MAX_CAUSE_DEPTH && seen.add(link);
                link = link.getCause()) {
            chain.add(link);
        }
        return chain;
    }
}
