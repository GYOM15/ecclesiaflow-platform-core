package com.ecclesiaflow.platform.rpc.s2s.interceptor;

import io.grpc.BindableService;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import org.springframework.aop.support.AopUtils;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Maps each gRPC full method name ({@code <service>/<Rpc>}, the Java method name capitalised as the
 * generated stubs do) to its {@link S2sScopeRequired} scope. Construction fails when a business RPC
 * declares no scope or an annotation names no RPC of its service, so either mistake surfaces at startup
 * rather than as a refused call in production.
 */
public class S2sScopeRegistry {

    /**
     * Generated services that cannot be annotated but must stay reachable (health probes, grpcurl). Both
     * reflection versions are listed: the runtime registers either, depending on the factory in use.
     */
    static final Set<String> INFRASTRUCTURE_SERVICES = Set.of(
            "grpc.health.v1.Health",
            "grpc.reflection.v1.ServerReflection",
            "grpc.reflection.v1alpha.ServerReflection");

    private final Map<String, String> methodToScope;

    public S2sScopeRegistry(List<BindableService> services) {
        Map<String, String> map = new HashMap<>();
        Set<String> unscoped = new TreeSet<>();
        Set<String> orphans = new TreeSet<>();
        for (BindableService svc : services) {
            ServerServiceDefinition definition = svc.bindService();
            String serviceName = definition.getServiceDescriptor().getName();
            Set<String> rpcs = new TreeSet<>();
            for (ServerMethodDefinition<?, ?> rpc : definition.getMethods()) {
                rpcs.add(rpc.getMethodDescriptor().getFullMethodName());
            }
            // Scan the target class: the logging aspects CGLIB-proxy gRPC beans, and the proxy subclass does not
            // carry the method annotations.
            for (Method m : AopUtils.getTargetClass(svc).getMethods()) {
                S2sScopeRequired ann = m.getAnnotation(S2sScopeRequired.class);
                if (ann == null) {
                    continue;
                }
                String fullName = serviceName + "/" + capitalize(m.getName());
                map.put(fullName, ann.value());
                if (!rpcs.contains(fullName)) {
                    orphans.add(fullName);
                }
            }
            if (!INFRASTRUCTURE_SERVICES.contains(serviceName)) {
                rpcs.stream().filter(rpc -> !map.containsKey(rpc)).forEach(unscoped::add);
            }
        }
        if (!unscoped.isEmpty() || !orphans.isEmpty()) {
            throw new IllegalStateException("s2s scope registry is incomplete. RPCs without @S2sScopeRequired: "
                    + unscoped + ". @S2sScopeRequired matching no RPC of its service: " + orphans + ".");
        }
        this.methodToScope = Map.copyOf(map);
    }

    public Optional<String> requiredScope(String fullMethodName) {
        return Optional.ofNullable(methodToScope.get(fullMethodName));
    }

    public boolean isInfrastructureService(String fullMethodName) {
        if (fullMethodName == null) {
            return false;
        }
        int slash = fullMethodName.indexOf('/');
        String serviceName = slash >= 0 ? fullMethodName.substring(0, slash) : fullMethodName;
        return INFRASTRUCTURE_SERVICES.contains(serviceName);
    }

    /** Exposed for tests and monitoring. */
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
