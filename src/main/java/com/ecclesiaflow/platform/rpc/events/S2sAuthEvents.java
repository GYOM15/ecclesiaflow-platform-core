package com.ecclesiaflow.platform.rpc.events;

/**
 * Application events emitted by the platform's gRPC interceptors when an
 * authentication or authorization decision is made.
 *
 * <p>The interceptors themselves never log: they publish these events through
 * Spring's {@code ApplicationEventPublisher} and the logging aspect listens on
 * the other side. This keeps the interceptors free of cross-cutting concerns
 * (single responsibility) and lets consumers add their own listeners for audit,
 * metrics or alerting without touching the library.</p>
 *
 * <p>Events are nested as {@code records} for compactness — they carry no
 * behaviour, only the minimum payload needed to produce a useful log line.</p>
 */
public final class S2sAuthEvents {

    private S2sAuthEvents() { /* utility container */ }

    /** Emitted when an outbound call cannot be made because the token provider is failing. */
    public record OutboundTokenUnavailable(String fullMethodName, String reason) {}

    /** Emitted when an inbound RPC is rejected because the {@code Authorization} header is missing or malformed. */
    public record InboundMissingHeader(String fullMethodName) {}

    /** Emitted when an inbound RPC is rejected because the JWT failed signature or expiry validation. */
    public record InboundInvalidToken(String fullMethodName, String reason) {}

    /** Emitted when an inbound RPC is rejected because the JWT is valid but lacks the required scope. */
    public record InboundMissingScope(String fullMethodName, String requiredScope) {}

    /**
     * Emitted when an inbound RPC is rejected fail-closed: the JWT carried the
     * generic scope but the called method declares no per-method scope and is
     * not an exempt infrastructure service. Usually means a business RPC was
     * added without its {@code @S2sScopeRequired} annotation.
     */
    public record InboundUnmappedMethod(String fullMethodName) {}

    /**
     * Emitted when an inbound RPC is rejected because the token's {@code azp}
     * (authorized party — the Keycloak client it was minted for) is not on the
     * allow-list. Distinct from {@link InboundMissingScope} on purpose: a
     * missing scope is a misconfiguration, a foreign client is a token minted
     * for something that has no business on the gRPC plane at all.
     *
     * @param azp the rejected authorized party, or {@code null} when the claim is absent
     */
    public record InboundForeignClient(String fullMethodName, String azp) {}

    /**
     * Emitted when an inbound RPC is <strong>accepted</strong>: token valid,
     * generic scope present, per-method scope present. Without this the audit
     * trail records only refusals, so a successful lateral call between modules
     * leaves no trace at all (finding F045).
     *
     * @param subject     the token's {@code sub}, already masked for logging
     * @param azp         the authorized party the token was minted for
     * @param methodScope the per-method scope that was satisfied
     */
    public record InboundAccepted(String fullMethodName, String subject, String azp, String methodScope) {}

    /**
     * Emitted when an inbound RPC is accepted through the infrastructure
     * bypass — a gRPC standard service (Health, Reflection) that carries no
     * {@code @S2sScopeRequired} annotation because we do not own it.
     *
     * @param serviceName the gRPC service the bypass applied to
     */
    public record InboundInfrastructureBypass(String fullMethodName, String serviceName, String subject, String azp) {}
}
