package com.ecclesiaflow.platform.security.surface;

import com.ecclesiaflow.platform.contract.ApiContract;
import com.ecclesiaflow.platform.contract.ContractOperation;

import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The surface of every operation of the module's contract, read and checked once at startup, and the rule a
 * request falls under. Built from the contract, so a misdeclared {@code x-ef-surface} stops the module before
 * it serves anything.
 */
public final class SurfaceIndex {

    private final ApiContract contract;
    private final Map<ContractOperation, Surface> surfaces = new IdentityHashMap<>();
    private final Map<Surface, Integer> counts = new EnumMap<>(Surface.class);
    private final int tokenless;

    /** @throws com.ecclesiaflow.platform.contract.InvalidApiContractException on a misdeclared {@code x-ef-surface} */
    public SurfaceIndex(ApiContract contract) {
        this.contract = contract;
        int open = 0;
        for (ContractOperation operation : contract.operations()) {
            Surface surface = Surface.of(operation);
            if (operation.tokenless()) {
                open++;
                continue;
            }
            surfaces.put(operation, surface);
            counts.merge(surface, 1, Integer::sum);
        }
        this.tokenless = open;
    }

    /**
     * The rule for a request that presents a token. A {@code security: []} operation and a technical path
     * outside the contract are not ruled; any other path outside the contract is console-only.
     *
     * @param path the path within the application, as received
     */
    public Resolution resolve(String method, String path) {
        Optional<ContractOperation> found = contract.find(method, path);
        if (found.isPresent()) {
            ContractOperation operation = found.get();
            return new Resolution(operation, operation.tokenless() ? null : surfaces.get(operation));
        }
        return new Resolution(null, TechnicalPaths.matches(path) ? null : Surface.CONSOLE);
    }

    /** Operations that take a token and fall under this surface. */
    public int count(Surface surface) {
        return counts.getOrDefault(surface, 0);
    }

    /** Operations declared {@code security: []}, which the rule never touches. */
    public int tokenlessCount() {
        return tokenless;
    }

    /**
     * @param operation the operation reached, {@code null} outside the contract
     * @param surface   the rule to apply, {@code null} when the request is not ruled
     */
    public record Resolution(ContractOperation operation, Surface surface) {

        public boolean ruled() {
            return surface != null;
        }
    }
}
