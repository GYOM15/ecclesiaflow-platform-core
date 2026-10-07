package com.ecclesiaflow.platform.rpc.events;

import com.ecclesiaflow.platform.logging.SecurityMaskingUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;

/**
 * A listener rather than an aspect: gRPC calls the interceptors outside Spring's proxy, so a pointcut on
 * {@code interceptCall()} would silently never fire.
 */
@Slf4j
public class S2sAuthEventListener {

    @EventListener
    public void onOutboundTokenUnavailable(S2sAuthEvents.OutboundTokenUnavailable event) {
        log.warn("S2S-OUT: ❌ Aborting {} — token unavailable ({})",
                event.fullMethodName(), SecurityMaskingUtils.sanitizeInfra(event.reason()));
    }

    @EventListener
    public void onInboundMissingHeader(S2sAuthEvents.InboundMissingHeader event) {
        log.warn("S2S-IN: ❌ Rejected {} — missing or malformed Authorization header",
                event.fullMethodName());
    }

    @EventListener
    public void onInboundInvalidToken(S2sAuthEvents.InboundInvalidToken event) {
        log.warn("S2S-IN: ❌ Rejected {} — invalid JWT ({})",
                event.fullMethodName(), SecurityMaskingUtils.sanitizeInfra(event.reason()));
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

    @EventListener
    public void onInboundAzpPolicy(S2sAuthEvents.InboundAzpPolicy event) {
        if (event.enforced()) {
            log.info("S2S-IN: ✅ azp allow-list enforced — clients allowed on this gRPC plane: {}",
                    event.allowedAzp());
            return;
        }
        log.warn("S2S-IN: ⚠ azp allow-list is empty — any client of the realm holding the generic scope "
                + "reaches this gRPC plane. Set ecclesiaflow.platform.rpc.s2s.allowed-azp to the backend "
                + "clients that call this module.");
    }

    /** INFO: the ordinary case, and the line an operator greps to learn whether anything got through. */
    @EventListener
    public void onInboundAccepted(S2sAuthEvents.InboundAccepted event) {
        log.info("S2S-IN: ✅ Accepted {} — client={} subject={} scope={}",
                event.fullMethodName(), event.azp(), event.subject(), event.methodScope());
    }

    /**
     * Health probes fire every few seconds, so DEBUG; nothing in the fleet calls reflection in production,
     * so a reflection call is a debugging session or an API enumeration, hence WARN.
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
