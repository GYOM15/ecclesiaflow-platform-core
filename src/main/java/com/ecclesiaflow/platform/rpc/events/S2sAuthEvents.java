package com.ecclesiaflow.platform.rpc.events;

import java.util.Set;

/** Decisions of the s2s interceptors, which never log: listeners turn them into logs, audit or metrics. */
public final class S2sAuthEvents {

    private S2sAuthEvents() { }

    public record OutboundTokenUnavailable(String fullMethodName, String reason) {}

    public record InboundMissingHeader(String fullMethodName) {}

    public record InboundInvalidToken(String fullMethodName, String reason) {}

    public record InboundMissingScope(String fullMethodName, String requiredScope) {}

    /** Usually a business RPC added without its {@code @S2sScopeRequired}. */
    public record InboundUnmappedMethod(String fullMethodName) {}

    /**
     * Distinct from a missing scope (a misconfiguration): the token was minted for a client with no
     * business on this plane. {@code azp} is {@code null} when the claim is absent.
     */
    public record InboundForeignClient(String fullMethodName, String azp) {}

    /** Published once at startup so an open plane is stated in the logs, not implied by a missing property. */
    public record InboundAzpPolicy(Set<String> allowedAzp) {

        public InboundAzpPolicy {
            allowedAzp = Set.copyOf(allowedAzp);
        }

        public boolean enforced() {
            return !allowedAzp.isEmpty();
        }
    }

    /** Without it only refusals leave a trace; {@code subject} is already masked. */
    public record InboundAccepted(String fullMethodName, String subject, String azp, String methodScope) {}

    public record InboundInfrastructureBypass(String fullMethodName, String serviceName, String subject, String azp) {}
}
