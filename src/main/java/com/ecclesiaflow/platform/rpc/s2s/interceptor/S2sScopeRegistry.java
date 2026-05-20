package com.ecclesiaflow.platform.rpc.s2s.interceptor;

import io.grpc.BindableService;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    private final Map<String, String> methodToScope;

    public S2sScopeRegistry(List<BindableService> services) {
        Map<String, String> map = new HashMap<>();
        for (BindableService svc : services) {
            String serviceName = svc.bindService().getServiceDescriptor().getName();
            for (Method m : svc.getClass().getMethods()) {
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
     * empty if the method was not annotated — meaning the generic
     * platform scope alone is enough.
     */
    public Optional<String> requiredScope(String fullMethodName) {
        return Optional.ofNullable(methodToScope.get(fullMethodName));
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
