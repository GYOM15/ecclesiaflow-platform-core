package com.ecclesiaflow.platform.rpc.s2s.interceptor;

import io.grpc.BindableService;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerServiceDefinition;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class S2sScopeRegistryTest {

    private static final String TEST_SERVICE = "ecclesiaflow.test.TestService";

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
        assertThat(reg.requiredScope(TEST_SERVICE + "/DoThing")).contains("ef:test:do");
        assertThat(reg.size()).isEqualTo(1);
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
        assertThat(reg.requiredScope(TEST_SERVICE + "/DoThing"))
                .as("the @S2sScopeRequired annotation must be seen through the CGLIB proxy")
                .contains("ef:test:do");
    }

    @Test
    void refusesToStartWhenAnRpcDeclaresNoScope() {
        // Without this check the gap only shows on the first call, as a PERMISSION_DENIED
        // in production.
        assertThatThrownBy(() -> new S2sScopeRegistry(List.of(new PartiallyAnnotatedService())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(TEST_SERVICE + "/DoOther")
                .hasMessageNotContaining(TEST_SERVICE + "/DoThing");
    }

    @Test
    void refusesToStartWhenAnAnnotationMatchesNoRpc() {
        // A Java method whose capitalised name is not an RPC (a helper, a renamed RPC,
        // a proto name that is not UpperCamelCase) guards nothing.
        assertThatThrownBy(() -> new S2sScopeRegistry(List.of(new OrphanAnnotationService())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(TEST_SERVICE + "/DoGhost");
    }

    @Test
    void infrastructureServicesNeedNoAnnotation() {
        assertThatCode(() -> new S2sScopeRegistry(List.of(new HealthLikeService())))
                .doesNotThrowAnyException();
    }

    private static ServerServiceDefinition definition(String service, String... rpcs) {
        ServerServiceDefinition.Builder builder = ServerServiceDefinition.builder(service);
        for (String rpc : rpcs) {
            MethodDescriptor<Object, Object> method = MethodDescriptor.newBuilder(NoopMarshaller.INSTANCE, NoopMarshaller.INSTANCE)
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName(MethodDescriptor.generateFullMethodName(service, rpc))
                    .build();
            builder.addMethod(method, (call, headers) -> new ServerCall.Listener<>() { });
        }
        return builder.build();
    }

    static class StubGrpcService implements BindableService {
        @Override
        public ServerServiceDefinition bindService() {
            return definition(TEST_SERVICE, "DoThing");
        }

        @S2sScopeRequired("ef:test:do")
        public void doThing() {
            // only the annotation matters to the registry
        }
    }

    static class PartiallyAnnotatedService implements BindableService {
        @Override
        public ServerServiceDefinition bindService() {
            return definition(TEST_SERVICE, "DoThing", "DoOther");
        }

        @S2sScopeRequired("ef:test:do")
        public void doThing() {
        }

        public void doOther() {
        }
    }

    static class OrphanAnnotationService implements BindableService {
        @Override
        public ServerServiceDefinition bindService() {
            return definition(TEST_SERVICE, "DoThing");
        }

        @S2sScopeRequired("ef:test:do")
        public void doThing() {
        }

        @S2sScopeRequired("ef:test:ghost")
        public void doGhost() {
        }
    }

    static class HealthLikeService implements BindableService {
        @Override
        public ServerServiceDefinition bindService() {
            return definition("grpc.health.v1.Health", "Check");
        }
    }

    private enum NoopMarshaller implements MethodDescriptor.Marshaller<Object> {
        INSTANCE;

        @Override
        public InputStream stream(Object value) {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public Object parse(InputStream stream) {
            return new Object();
        }
    }
}
