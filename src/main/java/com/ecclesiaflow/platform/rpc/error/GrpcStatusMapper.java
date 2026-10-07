package com.ecclesiaflow.platform.rpc.error;

import com.ecclesiaflow.platform.error.ErrorCategory;
import com.ecclesiaflow.platform.error.ErrorCategoryResolver;
import com.ecclesiaflow.platform.logging.SecurityMaskingUtils;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Caller errors keep their sanitized message because the caller may need it to fix the request;
 * internal failures and outages do not, their detail stays in the server log. Nor does a conflict
 * the persistence layer raised: its text names constraints, key values and rows, and the code alone
 * tells the caller what happened.
 */
public class GrpcStatusMapper {

    // Matched by name so the library does not drag these APIs onto every service.
    private static final Set<String> PERSISTENCE_FAILURES = Set.of(
            "org.springframework.dao.DataAccessException",
            "jakarta.persistence.PersistenceException");

    private final ErrorCategoryResolver categories;

    public GrpcStatusMapper(ErrorCategoryResolver categories) {
        this.categories = categories;
    }

    public Status toStatus(Throwable error) {
        if (error instanceof StatusRuntimeException raised) {
            return raised.getStatus();
        }
        if (error instanceof StatusException raised) {
            return raised.getStatus();
        }
        ErrorCategory category = categories.resolve(error);
        return codeOf(category).toStatus()
                .withDescription(describe(category, error))
                .withCause(error);
    }

    private static Status.Code codeOf(ErrorCategory category) {
        return switch (category) {
            case INVALID_ARGUMENT -> Status.Code.INVALID_ARGUMENT;
            case UNAUTHENTICATED -> Status.Code.UNAUTHENTICATED;
            case PERMISSION_DENIED -> Status.Code.PERMISSION_DENIED;
            case NOT_FOUND -> Status.Code.NOT_FOUND;
            case ALREADY_EXISTS -> Status.Code.ALREADY_EXISTS;
            case FAILED_PRECONDITION -> Status.Code.FAILED_PRECONDITION;
            case ABORTED -> Status.Code.ABORTED;
            case RESOURCE_EXHAUSTED -> Status.Code.RESOURCE_EXHAUSTED;
            case UNAVAILABLE -> Status.Code.UNAVAILABLE;
            case INTERNAL -> Status.Code.INTERNAL;
        };
    }

    private static String describe(ErrorCategory category, Throwable error) {
        return switch (category) {
            case INTERNAL -> "Internal error";
            case UNAVAILABLE -> "Dependency unavailable";
            case ALREADY_EXISTS -> raisedByPersistence(error) ? "Already exists" : sanitizedMessage(error);
            case ABORTED -> raisedByPersistence(error)
                    ? "Concurrent modification, retry the operation" : sanitizedMessage(error);
            default -> sanitizedMessage(error);
        };
    }

    private static String sanitizedMessage(Throwable error) {
        return error.getMessage() == null ? null : SecurityMaskingUtils.sanitizeInfra(error.getMessage());
    }

    private static boolean raisedByPersistence(Throwable error) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable link = error; link != null && seen.add(link); link = link.getCause()) {
            if (link instanceof SQLException) {
                return true;
            }
            for (Class<?> type = link.getClass(); type != null; type = type.getSuperclass()) {
                if (PERSISTENCE_FAILURES.contains(type.getName())) {
                    return true;
                }
            }
        }
        return false;
    }
}
