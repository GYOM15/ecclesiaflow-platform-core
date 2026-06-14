package com.ecclesiaflow.platform.rpc.s2s.interceptor;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class S2sScopeRegistryTest {

    /** No services registered: every business method is unmapped (fail-closed at the interceptor). */
    private final S2sScopeRegistry registry = new S2sScopeRegistry(List.of());

    @Test
    void noServicesMeansEmptyRegistry() {
        assertThat(registry.size()).isZero();
        assertThat(registry.requiredScope("ecclesiaflow.members.MembersService/NotifyAccountActivated"))
                .isEqualTo(Optional.empty());
    }

    @Test
    void exemptsHealthService() {
        assertThat(registry.isInfrastructureService("grpc.health.v1.Health/Check")).isTrue();
        assertThat(registry.isInfrastructureService("grpc.health.v1.Health/Watch")).isTrue();
    }

    @Test
    void exemptsReflectionServices() {
        assertThat(registry.isInfrastructureService(
                "grpc.reflection.v1.ServerReflection/ServerReflectionInfo")).isTrue();
        assertThat(registry.isInfrastructureService(
                "grpc.reflection.v1alpha.ServerReflection/ServerReflectionInfo")).isTrue();
    }

    @Test
    void doesNotExemptBusinessService() {
        assertThat(registry.isInfrastructureService(
                "ecclesiaflow.members.MembersService/NotifyAccountActivated")).isFalse();
    }

    @Test
    void handlesNullAndUnslashedNames() {
        assertThat(registry.isInfrastructureService(null)).isFalse();
        // A bare service name (no '/RpcName') is matched on the whole string.
        assertThat(registry.isInfrastructureService("grpc.health.v1.Health")).isTrue();
        assertThat(registry.isInfrastructureService("some.random.Service")).isFalse();
    }
}
