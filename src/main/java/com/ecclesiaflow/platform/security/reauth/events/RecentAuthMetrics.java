package com.ecclesiaflow.platform.security.reauth.events;

import com.ecclesiaflow.platform.security.reauth.ReauthenticationRequiredException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.event.EventListener;

/**
 * Refusals of a recent sign-in, per reason: a burst of {@code SECOND_FACTOR_NOT_PRESENTED} after sign-ins means the
 * realm's flows no longer write {@code amr}, and every linked account with an app is stuck until they do.
 */
public class RecentAuthMetrics {

    /** Exported to Prometheus as {@code ef_recent_auth_refusals_total}. */
    public static final String REFUSALS_METRIC = "ef.recent.auth.refusals";

    private final MeterRegistry registry;

    /** @param registry may be {@code null}: nothing is then counted. */
    public RecentAuthMetrics(MeterRegistry registry) {
        this.registry = registry;
        if (registry == null) {
            return;
        }
        // Published at zero: increase() cannot see the first increment of a series that starts with it.
        for (ReauthenticationRequiredException.Reason reason : ReauthenticationRequiredException.Reason.values()) {
            counter(reason);
        }
    }

    @EventListener
    public void onRefused(RecentAuthEvents.Refused event) {
        if (registry != null) {
            counter(event.reason()).increment();
        }
    }

    private Counter counter(ReauthenticationRequiredException.Reason reason) {
        return Counter.builder(REFUSALS_METRIC)
                .description("Sensitive gestures refused until the user signs in again")
                .tag("reason", reason.name())
                .register(registry);
    }
}
