package com.ecclesiaflow.platform.security.surface;

import java.util.Collection;
import java.util.Set;

/**
 * The clients of the platform's operating tools (the back-office, the moderation and billing command lines). Their
 * tokens carry the modules' audience like any other, so without this rule the owner's back-office token would read
 * his own conversations through a member route, and the back-office's relay would be the only barrier.
 */
public final class OperatingClients {

    private final Set<String> clients;

    public OperatingClients(Collection<String> clients) {
        this.clients = Set.copyOf(clients);
    }

    /**
     * Whether a token issued to {@code azp} may reach an operation of this surface: an operating client reaches the
     * platform's duties and nothing else, whatever its scopes; any other client is left to the surface's own rule.
     */
    public boolean admit(String azp, Surface surface) {
        return surface.platform() || azp == null || !clients.contains(azp);
    }

    public Set<String> clients() {
        return clients;
    }
}
