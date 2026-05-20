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
}
