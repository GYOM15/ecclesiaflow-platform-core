package com.ecclesiaflow.platform.rpc.error;

import com.ecclesiaflow.platform.error.DataIntegrityTestData;
import com.ecclesiaflow.platform.error.ErrorCategory;
import com.ecclesiaflow.platform.error.ErrorCategoryResolver;
import com.ecclesiaflow.platform.error.ExceptionClassifier;
import io.grpc.Metadata;
import io.grpc.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.OptimisticLockingFailureException;

import java.net.ConnectException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class GrpcStatusMapperTest {

    private final GrpcStatusMapper mapper = new GrpcStatusMapper(new ErrorCategoryResolver(List.of()));

    @ParameterizedTest
    @EnumSource(ErrorCategory.class)
    @DisplayName("every category has the gRPC code of the same name")
    void everyCategoryMaps(ErrorCategory category) {
        GrpcStatusMapper fixed = new GrpcStatusMapper(new ErrorCategoryResolver(
                List.<ExceptionClassifier>of(error -> Optional.of(category))));

        assertThat(fixed.toStatus(new RuntimeException()).getCode().name()).isEqualTo(category.name());
    }

    @Test
    @DisplayName("a caller error carries the sanitized exception message")
    void callerErrorKeepsSanitizedMessage() {
        Status status = mapper.toStatus(new IllegalArgumentException("church_id must be a UUID, see https://kc.internal/x"));

        assertThat(status.getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(status.getDescription()).startsWith("church_id must be a UUID").doesNotContain("kc.internal");
    }

    @Test
    @DisplayName("a caller error without a message has no description")
    void callerErrorWithoutMessage() {
        assertThat(mapper.toStatus(new IllegalArgumentException()).getDescription()).isNull();
    }

    @Test
    @DisplayName("an internal failure never reveals its message to the caller")
    void internalIsOpaque() {
        Status status = mapper.toStatus(new RuntimeException("password authentication failed for user ef_app"));

        assertThat(status.getCode()).isEqualTo(Status.Code.INTERNAL);
        assertThat(status.getDescription()).isEqualTo("Internal error");
    }

    @Test
    @DisplayName("a unique violation is ALREADY_EXISTS without the driver's constraint name or values")
    void uniqueViolation() {
        Status status = mapper.toStatus(DataIntegrityTestData.violation(DataIntegrityTestData.UNIQUE_VIOLATION,
                "ERROR: duplicate key value violates unique constraint \"uk_membership_church_member\" "
                        + "Detail: Key (church_id, member_id)=(3f6c, 9a1b) already exists."));

        assertThat(status.getCode()).isEqualTo(Status.Code.ALREADY_EXISTS);
        assertThat(status.getDescription()).isEqualTo("Already exists");
    }

    @Test
    @DisplayName("a duplicate a module raises on purpose keeps its own sanitized message")
    void moduleDuplicateKeepsItsMessage() {
        GrpcStatusMapper classified = new GrpcStatusMapper(new ErrorCategoryResolver(List.of(
                ExceptionClassifier.byType(Map.of(IllegalStateException.class, ErrorCategory.ALREADY_EXISTS)))));

        Status status = classified.toStatus(new IllegalStateException("This email already belongs to a member record"));

        assertThat(status.getCode()).isEqualTo(Status.Code.ALREADY_EXISTS);
        assertThat(status.getDescription()).isEqualTo("This email already belongs to a member record");
    }

    @Test
    @DisplayName("a module duplicate that wraps the driver's refusal gets the fixed description")
    void moduleDuplicateOverDriverText() {
        GrpcStatusMapper classified = new GrpcStatusMapper(new ErrorCategoryResolver(List.of(
                ExceptionClassifier.byType(Map.of(IllegalStateException.class, ErrorCategory.ALREADY_EXISTS)))));

        Status status = classified.toStatus(new IllegalStateException("could not save membership: "
                + "duplicate key value violates unique constraint \"uk_membership_church_member\"",
                new SQLException("duplicate key", DataIntegrityTestData.UNIQUE_VIOLATION)));

        assertThat(status.getCode()).isEqualTo(Status.Code.ALREADY_EXISTS);
        assertThat(status.getDescription()).isEqualTo("Already exists");
    }

    @Test
    @DisplayName("a lost optimistic lock is ABORTED without the entity name or id")
    void optimisticLock() {
        Status status = mapper.toStatus(new OptimisticLockingFailureException(
                "Row was updated or deleted by another transaction : [com.ecclesiaflow.church.io.ChurchEntity#3f6c]"));

        assertThat(status.getCode()).isEqualTo(Status.Code.ABORTED);
        assertThat(status.getDescription()).isEqualTo("Concurrent modification, retry the operation");
    }

    @Test
    @DisplayName("a foreign key violation is INTERNAL and opaque, never ALREADY_EXISTS")
    void foreignKeyViolation() {
        Status status = mapper.toStatus(DataIntegrityTestData.foreignKeyViolation());

        assertThat(status.getCode()).isEqualTo(Status.Code.INTERNAL);
        assertThat(status.getDescription()).isEqualTo("Internal error");
    }

    @Test
    @DisplayName("a NOT NULL violation is INTERNAL and opaque, never ALREADY_EXISTS")
    void notNullViolation() {
        Status status = mapper.toStatus(DataIntegrityTestData.notNullViolation());

        assertThat(status.getCode()).isEqualTo(Status.Code.INTERNAL);
        assertThat(status.getDescription()).isEqualTo("Internal error");
    }

    @Test
    @DisplayName("an ABORTED a module raises on purpose keeps its own message, even over a cause cycle")
    void moduleAbortKeepsItsMessage() {
        GrpcStatusMapper classified = new GrpcStatusMapper(new ErrorCategoryResolver(List.of(
                ExceptionClassifier.byType(Map.of(IllegalStateException.class, ErrorCategory.ABORTED)))));
        IllegalStateException superseded = new IllegalStateException("Batch was closed meanwhile");
        RuntimeException wrapper = new RuntimeException("retrying", superseded);
        superseded.initCause(wrapper);

        Status status = classified.toStatus(superseded);

        assertThat(status.getCode()).isEqualTo(Status.Code.ABORTED);
        assertThat(status.getDescription()).isEqualTo("Batch was closed meanwhile");
    }

    @Test
    @DisplayName("an outage says so without naming the dependency's address")
    void unavailableIsOpaque() {
        Status status = mapper.toStatus(new IllegalStateException("x", new ConnectException("keycloak:8080 refused")));

        assertThat(status.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
        assertThat(status.getDescription()).isEqualTo("Dependency unavailable");
    }

    @Test
    @DisplayName("a status the service raised on purpose passes through unchanged")
    void statusPassesThrough() {
        Status raised = Status.NOT_FOUND.withDescription("no such church");

        assertThat(mapper.toStatus(raised.asRuntimeException())).isSameAs(raised);
        assertThat(mapper.toStatus(raised.asException(new Metadata()))).isSameAs(raised);
    }

    @Test
    @DisplayName("the original exception stays attached for the server-side log")
    void causeAttached() {
        RuntimeException boom = new RuntimeException("boom");

        assertThat(mapper.toStatus(boom).getCause()).isSameAs(boom);
    }
}
