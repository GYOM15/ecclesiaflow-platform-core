package com.ecclesiaflow.platform.rpc.s2s.interceptor;

import io.grpc.BindableService;
import io.grpc.ServerServiceDefinition;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;

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

    @Test
    void indexesAnnotatedMethodOnPlainBean() {
        S2sScopeRegistry reg = new S2sScopeRegistry(List.of(new StubGrpcService()));
        assertThat(reg.requiredScope("ecclesiaflow.test.TestService/DoThing"))
                .contains("ef:test:do");
    }

    /**
     * The gRPC impl beans are CGLIB-proxied at runtime (logging aspects), and the
     * generated proxy subclass does not carry the method-level {@link S2sScopeRequired}
     * annotations. The registry must scan the target class — otherwise every annotated
     * RPC is left unmapped and the fail-closed interceptor rejects it.
     */
    @Test
    void indexesAnnotatedMethodThroughCglibProxy() {
        ProxyFactory pf = new ProxyFactory(new StubGrpcService());
        pf.setProxyTargetClass(true); // force a CGLIB subclass proxy, like Spring's AOP
        pf.addAdvice((MethodInterceptor) MethodInvocation::proceed);
        BindableService proxied = (BindableService) pf.getProxy();

        S2sScopeRegistry reg = new S2sScopeRegistry(List.of(proxied));
        assertThat(reg.requiredScope("ecclesiaflow.test.TestService/DoThing"))
                .as("the @S2sScopeRequired annotation must be seen through the CGLIB proxy")
                .contains("ef:test:do");
    }

    /** Minimal BindableService stub with one scoped RPC. */
    static class StubGrpcService implements BindableService {
        @Override
        public ServerServiceDefinition bindService() {
            return ServerServiceDefinition.builder("ecclesiaflow.test.TestService").build();
        }

        @S2sScopeRequired("ef:test:do")
        public void doThing() {
            // no-op; only its annotation matters to the registry
        }
    }
}
