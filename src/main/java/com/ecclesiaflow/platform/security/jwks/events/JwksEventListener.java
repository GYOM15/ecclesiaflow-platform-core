package com.ecclesiaflow.platform.security.jwks.events;

import com.ecclesiaflow.platform.logging.SecurityMaskingUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;

/**
 * A routine refresh stays at DEBUG; the first load, a rotation and the end of an outage are worth a line.
 * A failure is a WARN while the last key set still serves, an ERROR once tokens are refused.
 */
@Slf4j
public class JwksEventListener {

    @EventListener
    public void onKeySetLoaded(JwksEvents.KeySetLoaded event) {
        if (event.afterFailure()) {
            log.info("JWKS: ✅ Key set reloaded after an outage — {} key(s)", event.keyCount());
        } else if (event.trigger() == JwksEvents.Trigger.REFRESH) {
            log.debug("JWKS: Key set refreshed — {} key(s)", event.keyCount());
        } else {
            log.info("JWKS: ✅ Key set loaded ({}) — {} key(s)", label(event.trigger()), event.keyCount());
        }
    }

    @EventListener
    public void onReloadFailed(JwksEvents.ReloadFailed event) {
        String cause = event.cause().getClass().getSimpleName() + ": "
                + SecurityMaskingUtils.rootMessage(event.cause());
        if (event.servingLastKnown()) {
            log.warn("JWKS: ⚠ {} reload failed, the last key set still serves (outage {} s) — {}",
                    label(event.trigger()), event.outage().toSeconds(), cause);
        } else if (event.outage() == null) {
            log.error("JWKS: ❌ {} failed, no key set to check tokens with — {}", label(event.trigger()), cause);
        } else {
            log.error("JWKS: ❌ {} reload failed and the outage ({} s) exceeds the tolerance, tokens are refused"
                    + " — {}", label(event.trigger()), event.outage().toSeconds(), cause);
        }
    }

    private static String label(JwksEvents.Trigger trigger) {
        return switch (trigger) {
            case FIRST_LOAD -> "first load";
            case REFRESH -> "scheduled";
            case UNKNOWN_KEY -> "unknown key id";
        };
    }
}
