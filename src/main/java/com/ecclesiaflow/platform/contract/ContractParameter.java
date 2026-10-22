package com.ecclesiaflow.platform.contract;

import java.util.Map;
import java.util.Objects;

/**
 * A parameter of a {@link ContractOperation}, after local {@code $ref}s are resolved.
 *
 * @param name     the parameter name as written in the contract
 * @param in       {@code path}, {@code query}, {@code header} or {@code cookie}
 * @param required the contract's {@code required}, {@code false} when absent
 * @param schema   the parameter's schema, its own {@code $ref} resolved; empty when it declares none
 */
public record ContractParameter(String name, String in, boolean required, Map<String, Object> schema) {

    public ContractParameter {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(in, "in");
        schema = schema == null ? Map.of() : ApiContractLoader.immutable(schema);
    }

    /** Header names compare case-insensitively, as HTTP does; every other location compares exactly. */
    public boolean is(String location, String parameterName) {
        if (!in.equals(location)) {
            return false;
        }
        return "header".equals(location) ? name.equalsIgnoreCase(parameterName) : name.equals(parameterName);
    }
}
