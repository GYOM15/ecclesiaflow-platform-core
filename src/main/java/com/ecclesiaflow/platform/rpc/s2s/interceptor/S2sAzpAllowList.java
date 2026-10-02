package com.ecclesiaflow.platform.rpc.s2s.interceptor;

import com.ecclesiaflow.platform.rpc.s2s.S2sProperties;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Keycloak clients ({@code azp} claim) allowed on the inbound gRPC plane.
 *
 * <p>An empty list leaves the check off. Blank entries are dropped, so a property bound
 * from an unset environment variable reads as "not configured" rather than as a list
 * nothing can match.</p>
 */
public final class S2sAzpAllowList {

    private final Set<String> clientIds;

    private S2sAzpAllowList(Set<String> clientIds) {
        this.clientIds = Set.copyOf(clientIds);
    }

    /**
     * @throws IllegalStateException when {@code require-allowed-azp} is set and the list is empty
     */
    public static S2sAzpAllowList from(S2sProperties props) {
        Set<String> clientIds = normalize(props.getAllowedAzp());
        if (props.isRequireAllowedAzp() && clientIds.isEmpty()) {
            throw new IllegalStateException("ecclesiaflow.platform.rpc.s2s.require-allowed-azp is true but "
                    + "ecclesiaflow.platform.rpc.s2s.allowed-azp is empty: list the backend clients "
                    + "allowed to call this module.");
        }
        return new S2sAzpAllowList(clientIds);
    }

    public boolean isEnforced() {
        return !clientIds.isEmpty();
    }

    /** An absent claim is refused once the list is set. */
    public boolean permits(String azp) {
        return clientIds.isEmpty() || (azp != null && clientIds.contains(azp));
    }

    public Set<String> clientIds() {
        return clientIds;
    }

    private static Set<String> normalize(List<String> configured) {
        Set<String> result = new HashSet<>();
        if (configured != null) {
            for (String entry : configured) {
                if (entry != null && !entry.isBlank()) {
                    result.add(entry.trim());
                }
            }
        }
        return result;
    }
}
