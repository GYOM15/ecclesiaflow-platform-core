package com.ecclesiaflow.platform.security.surface;

import java.util.List;

/**
 * The module's security chain and its contract disagree on which routes need no token. Thrown at startup:
 * such a module would either open an operation the contract says needs a token, or lock out a caller the
 * contract says needs none.
 */
public class InvalidSurfaceConfigurationException extends IllegalStateException {

    private final List<String> violations;

    private InvalidSurfaceConfigurationException(String message, List<String> violations) {
        super(message);
        this.violations = List.copyOf(violations);
    }

    public static InvalidSurfaceConfigurationException mismatches(List<String> violations) {
        return new InvalidSurfaceConfigurationException("The security chain and the OpenAPI contract disagree on "
                + "routes open without a token:\n - " + String.join("\n - ", violations), violations);
    }

    public static InvalidSurfaceConfigurationException noPrivilegeEvaluator() {
        String reason = "No unique WebInvocationPrivilegeEvaluator bean: the routes open without a token cannot be "
                + "checked against the contract";
        return new InvalidSurfaceConfigurationException(reason, List.of(reason));
    }

    public List<String> violations() {
        return violations;
    }
}
