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

import java.net.ConnectException;
import java.util.List;
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
    @DisplayName("a unique violation is ALREADY_EXISTS")
    void uniqueViolation() {
        assertThat(mapper.toStatus(DataIntegrityTestData.uniqueViolation()).getCode())
                .isEqualTo(Status.Code.ALREADY_EXISTS);
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
