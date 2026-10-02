package com.ecclesiaflow.platform.ratelimit.events;

import com.ecclesiaflow.platform.logging.SecurityMaskingUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;

/**
 * Turns rate-limiter events into operator log lines.
 *
 * <p>WARN in both directions: a limiter that silently stops counting looks
 * exactly like one that works, and that is how an outage goes unnoticed.
 */
@Slf4j
public class RateLimitEventListener {

    @EventListener
    public void onCounterUnavailable(RateLimitEvents.CounterUnavailable event) {
        String cause = event.cause() == null
                ? "Redis returned no count"
                : event.cause().getClass().getSimpleName() + ": " + SecurityMaskingUtils.rootMessage(event.cause());
        if (event.failOpen()) {
            log.warn("RATE-LIMIT: ⚠ Rule '{}' not enforced — the counter could not be read ({})",
                    event.ruleName(), cause);
        } else {
            log.warn("RATE-LIMIT: ❌ Rule '{}' refusing — the counter could not be read ({})",
                    event.ruleName(), cause);
        }
    }
}
