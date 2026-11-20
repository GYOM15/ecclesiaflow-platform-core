package com.ecclesiaflow.platform.security.surface;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Objects;

/**
 * Bound to {@code ecclesiaflow.security.surface.*}. Closed by default: a module that sets nothing enforces its
 * surface, and {@code log-only} exists only for a module the deploy lists while it is being switched over.
 */
@ConfigurationProperties(prefix = "ecclesiaflow.security.surface")
public class SurfaceProperties {

    /** The Keycloak clients of the back-office and of the moderation and billing command lines. */
    public static final List<String> DEFAULT_OPERATING_CLIENTS =
            List.of("ecclesiaflow-backoffice", "ecclesiaflow-moderation-cli", "ecclesiaflow-billing-cli");

    private SurfaceMode mode = SurfaceMode.ENFORCE;

    private List<String> operatingClients = DEFAULT_OPERATING_CLIENTS;

    public SurfaceMode getMode() {
        return mode;
    }

    /** A blank value keeps {@code enforce}: an emptied variable must not open anything. */
    public void setMode(SurfaceMode mode) {
        this.mode = mode == null ? SurfaceMode.ENFORCE : mode;
    }

    /** The {@code azp} values whose tokens reach platform operations only. */
    public List<String> getOperatingClients() {
        return operatingClients;
    }

    /** An emptied list keeps the default: it must not open member and console operations to these clients. */
    public void setOperatingClients(List<String> operatingClients) {
        List<String> named = operatingClients == null ? List.of() : operatingClients.stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(client -> !client.isEmpty())
                .distinct()
                .toList();
        this.operatingClients = named.isEmpty() ? DEFAULT_OPERATING_CLIENTS : named;
    }
}
