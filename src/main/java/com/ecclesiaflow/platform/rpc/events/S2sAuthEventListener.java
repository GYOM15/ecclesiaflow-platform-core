package com.ecclesiaflow.platform.rpc.events;

import com.ecclesiaflow.platform.rpc.events.S2sAuthEvents;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;

/**
 * Subscribes to authentication and authorization events emitted by the gRPC
 * interceptors and turns them into operator-facing log lines.
 *
 * <p>Why a listener instead of an aspect: gRPC invokes {@code ServerInterceptor}
 * and {@code ClientInterceptor} directly, bypassing Spring's proxy. Spring AOP
 * pointcuts on {@code interceptCall()} would silently no-op. Events are the
 * idiomatic Spring escape hatch for that case — they also let downstream
 * consumers add their own audit or metrics listeners with zero coupling.</p>
 *
 * @author EcclesiaFlow Team
 * @since 0.1.0
 */
@Slf4j
public class S2sAuthEventListener {

    @EventListener
    public void onOutboundTokenUnavailable(S2sAuthEvents.OutboundTokenUnavailable event) {
        log.warn("S2S-OUT: ❌ Aborting {} — token unavailable ({})",
                event.fullMethodName(), event.reason());
    }

    @EventListener
    public void onInboundMissingHeader(S2sAuthEvents.InboundMissingHeader event) {
        log.warn("S2S-IN: ❌ Rejected {} — missing or malformed Authorization header",
                event.fullMethodName());
    }

    @EventListener
    public void onInboundInvalidToken(S2sAuthEvents.InboundInvalidToken event) {
        log.warn("S2S-IN: ❌ Rejected {} — invalid JWT ({})",
                event.fullMethodName(), event.reason());
    }

    @EventListener
    public void onInboundMissingScope(S2sAuthEvents.InboundMissingScope event) {
        log.warn("S2S-IN: ❌ Rejected {} — token lacks required scope {}",
                event.fullMethodName(), event.requiredScope());
    }

    @EventListener
    public void onInboundUnmappedMethod(S2sAuthEvents.InboundUnmappedMethod event) {
        log.warn("S2S-IN: ❌ Rejected {} — no per-method scope declared (fail-closed); "
                + "annotate the RPC with @S2sScopeRequired or whitelist it as infrastructure",
                event.fullMethodName());
    }

    @EventListener
    public void onInboundForeignClient(S2sAuthEvents.InboundForeignClient event) {
        log.warn("S2S-IN: ❌ Rejected {} — token minted for client '{}', which is not on the s2s allow-list",
                event.fullMethodName(), event.azp() == null ? "<no azp claim>" : event.azp());
    }

    /**
     * INFO, not WARN: this is the ordinary case. It exists so the audit trail
     * shows who called what, and it is the line an operator greps after an
     * incident to answer « did anything actually get through ».
     */
    @EventListener
    public void onInboundAccepted(S2sAuthEvents.InboundAccepted event) {
        log.info("S2S-IN: ✅ Accepted {} — client={} subject={} scope={}",
                event.fullMethodName(), event.azp(), event.subject(), event.methodScope());
    }

    /**
     * Split by service, and deliberately not all at the same level. The health
     * probe fires every few seconds: logging it at WARN would bury every real
     * signal within the hour, so it goes to DEBUG. Reflection is another matter
     * — nothing in this fleet calls it in production, so a reflection call is
     * either a debugging session or someone enumerating the API surface, and
     * that is worth a WARN.
     */
    @EventListener
    public void onInboundInfrastructureBypass(S2sAuthEvents.InboundInfrastructureBypass event) {
        if (event.serviceName() != null && event.serviceName().startsWith("grpc.reflection.")) {
            log.warn("S2S-IN: ⚠ Reflection call {} accepted through the infrastructure bypass — "
                    + "client={} subject={}. Nothing in production should enumerate the RPC surface.",
                    event.fullMethodName(), event.azp(), event.subject());
            return;
        }
        log.debug("S2S-IN: ✅ Accepted {} through the infrastructure bypass — client={} subject={}",
                event.fullMethodName(), event.azp(), event.subject());
    }
}
