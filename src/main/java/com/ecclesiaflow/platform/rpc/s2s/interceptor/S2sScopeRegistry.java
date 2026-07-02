package com.ecclesiaflow.platform.rpc.s2s.interceptor;

import io.grpc.BindableService;
import org.springframework.aop.support.AopUtils;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Maps each gRPC full method name to the scope a caller must carry to
 * invoke it. Populated once at startup from {@link S2sScopeRequired}
 * annotations on the public methods of every {@link BindableService}
 * Spring bean.
 *
 * <p>The full method name follows gRPC's wire convention
 * {@code <serviceFullName>/<RpcName>} — the service full name comes
 * straight from the gRPC service descriptor, the RPC name is the Java
 * method name capitalised (the same transform gRPC's generated stubs
 * apply). Example:
 * {@code ecclesiaflow.members.MembersService/NotifyAccountActivated}.</p>
 *
 * <p>Single responsibility: scan + answer the question
 * "does this full method name require an extra scope?". The
 * authentication and the JWT-side scope check live in
 * {@link S2sAuthServerInterceptor}.</p>
 */
public class S2sScopeRegistry {

    /**
     * gRPC standard infrastructure services that are intentionally
     * unannotated. We don't own their source code and cannot add
     * {@link S2sScopeRequired} to their generated methods, yet they must
     * stay reachable — the Health service backs Kubernetes liveness /
     * readiness probes and the reflection service backs grpcurl-style
     * tooling. The {@link S2sAuthServerInterceptor} fails closed on any
     * other unannotated RPC, so these service names are explicitly
     * exempted by their gRPC service full name (the part before the
     * {@code /} in the full method name).
     *
     * <p>Both the {@code v1} and the legacy {@code v1alpha} reflection
     * service names are listed because the gRPC runtime may register
     * either depending on the {@code ProtoReflectionService} factory in
     * use.</p>
     */
    static final Set<String> INFRASTRUCTURE_SERVICES = Set.of(
            "grpc.health.v1.Health",
            "grpc.reflection.v1.ServerReflection",
            "grpc.reflection.v1alpha.ServerReflection");

    private final Map<String, String> methodToScope;

    public S2sScopeRegistry(List<BindableService> services) {
        Map<String, String> map = new HashMap<>();
        for (BindableService svc : services) {
            String serviceName = svc.bindService().getServiceDescriptor().getName();
            // Scan the target class, not svc.getClass(): gRPC impl beans are CGLIB-proxied
            // by the logging aspects, and the generated proxy subclass does not carry the
            // method-level @S2sScopeRequired annotations. AopUtils.getTargetClass unwraps the
            // proxy (and is a no-op for an unproxied bean), so the scan sees the real methods.
            for (Method m : AopUtils.getTargetClass(svc).getMethods()) {
                S2sScopeRequired ann = m.getAnnotation(S2sScopeRequired.class);
                if (ann == null) {
                    continue;
                }
                String fullName = serviceName + "/" + capitalize(m.getName());
                map.put(fullName, ann.value());
            }
        }
        this.methodToScope = Map.copyOf(map);
    }

    /**
     * Returns the scope required to invoke the given gRPC RPC, or
     * empty if the method was not annotated — meaning no per-method
     * scope was declared for it.
     */
    public Optional<String> requiredScope(String fullMethodName) {
        return Optional.ofNullable(methodToScope.get(fullMethodName));
    }

    /**
     * Whether the given gRPC full method name belongs to an exempt
     * infrastructure service ({@link #INFRASTRUCTURE_SERVICES}). Such
     * calls bypass the per-method scope requirement entirely so that
     * health probes and reflection tooling keep working even though
     * their methods carry no {@link S2sScopeRequired} annotation.
     */
    public boolean isInfrastructureService(String fullMethodName) {
        if (fullMethodName == null) {
            return false;
        }
        int slash = fullMethodName.indexOf('/');
        String serviceName = slash >= 0 ? fullMethodName.substring(0, slash) : fullMethodName;
        return INFRASTRUCTURE_SERVICES.contains(serviceName);
    }

    /** Visible for tests / monitoring: total number of RPCs that have a per-method scope. */
    public int size() {
        return methodToScope.size();
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
