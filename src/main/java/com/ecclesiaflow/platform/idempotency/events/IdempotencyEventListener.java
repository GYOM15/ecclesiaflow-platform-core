package com.ecclesiaflow.platform.idempotency.events;

import com.ecclesiaflow.platform.logging.SecurityMaskingUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;

/**
 * A replay after a lost response is routine, so DEBUG; a race settled by the unique index is rare and proves
 * the index exists, so INFO; a reused key is a client bug the app cannot recover from, so WARN.
 */
@Slf4j
public class IdempotencyEventListener {

    @EventListener
    public void onReplayed(IdempotencyEvents.Replayed event) {
        if (event.raced()) {
            log.info("IDEMPOTENCY: ✅ {} — concurrent duplicate settled by the unique index, winner replayed "
                    + "(author={} key={})", event.operation(), SecurityMaskingUtils.maskId(event.author()),
                    SecurityMaskingUtils.maskId(event.key()));
            return;
        }
        log.debug("IDEMPOTENCY: {} replayed (author={} key={})", event.operation(),
                SecurityMaskingUtils.maskId(event.author()), SecurityMaskingUtils.maskId(event.key()));
    }

    @EventListener
    public void onConflicted(IdempotencyEvents.Conflicted event) {
        log.warn("IDEMPOTENCY: ⚠ {} refused a key reused for a different request{} (author={} key={})",
                event.operation(), event.raced() ? " in flight" : "",
                SecurityMaskingUtils.maskId(event.author()), SecurityMaskingUtils.maskId(event.key()));
    }
}
