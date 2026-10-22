package com.ecclesiaflow.platform.events.retry.events;

import com.ecclesiaflow.platform.logging.SecurityMaskingUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;

import java.util.Locale;

/** Queue names are the module's own configuration, logged as they are; reasons are masked. */
@Slf4j
public class DelayedRetryEventListener {

    @EventListener
    public void onRetryScheduled(DelayedRetryEvents.RetryScheduled event) {
        log.warn("EVENT-RETRY: ⚠️ Delivery on {} failed, attempt {}/{} in {} through {} - {}",
                event.queue(), event.attempt(), event.stages(), event.delay(), event.retryQueue(),
                SecurityMaskingUtils.sanitizeInfra(event.reason()));
    }

    @EventListener
    public void onDeadLettered(DelayedRetryEvents.DeadLettered event) {
        log.error("EVENT-RETRY: ❌ Delivery on {} dead-lettered ({}) after delayed_retries={} - {}",
                event.queue(), event.cause().name().toLowerCase(Locale.ROOT), event.retries(),
                SecurityMaskingUtils.sanitizeInfra(event.reason()));
    }
}
