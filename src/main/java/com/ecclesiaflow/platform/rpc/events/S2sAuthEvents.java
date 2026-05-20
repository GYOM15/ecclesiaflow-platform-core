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
}
