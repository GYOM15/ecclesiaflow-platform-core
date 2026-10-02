package com.ecclesiaflow.platform.events.outbox.events;

import com.ecclesiaflow.platform.events.outbox.relay.OutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.event.EventListener;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Alert on the oldest pending age: it grows whether the broker is down, the relay thread is gone or
 * a message keeps failing. No routing-key tag, so the series stay bounded.
 */
public class OutboxRelayMetrics {

    public static final String RELAY_METRIC = "ecclesiaflow.outbox.relay";
    public static final String CYCLE_FAILURE_METRIC = "ecclesiaflow.outbox.relay.cycle.failures";
    public static final String PENDING_METRIC = "ecclesiaflow.outbox.pending";
    public static final String PARKED_METRIC = "ecclesiaflow.outbox.parked";
    public static final String OLDEST_PENDING_AGE_METRIC = "ecclesiaflow.outbox.oldest.pending.age";

    private final MeterRegistry registry;

    /** @param registry may be {@code null}: nothing is then counted nor read. */
    public OutboxRelayMetrics(MeterRegistry registry, OutboxRepository repository, Clock clock) {
        this.registry = registry;
        if (registry == null) {
            return;
        }
        Gauge.builder(PENDING_METRIC, () -> readOrNaN(repository::countPending))
                .description("Outbox rows waiting to be relayed")
                .register(registry);
        Gauge.builder(PARKED_METRIC, () -> readOrNaN(repository::countParked))
                .description("Outbox rows set aside after exhausting their attempts")
                .register(registry);
        Gauge.builder(OLDEST_PENDING_AGE_METRIC, () -> readOrNaN(() -> repository.oldestPendingCreatedAt()
                        .map(oldest -> Duration.between(oldest, clock.instant()).toSeconds())
                        .orElse(0L)))
                .description("Age of the oldest outbox row waiting to be relayed")
                .baseUnit("seconds")
                .register(registry);
    }

    @EventListener
    public void onMessageRelayed(OutboxRelayEvents.MessageRelayed event) {
        countRelay("sent", 1);
    }

    @EventListener
    public void onRetryScheduled(OutboxRelayEvents.RetryScheduled event) {
        countRelay("retried", 1);
    }

    @EventListener
    public void onMessageParked(OutboxRelayEvents.MessageParked event) {
        countRelay("parked", 1);
    }

    @EventListener
    public void onMessagesDeferred(OutboxRelayEvents.MessagesDeferred event) {
        countRelay("deferred", event.count());
    }

    @EventListener
    public void onRelayCycleFailed(OutboxRelayEvents.RelayCycleFailed event) {
        if (registry != null) {
            Counter.builder(CYCLE_FAILURE_METRIC)
                    .tag("task", event.task().name().toLowerCase(Locale.ROOT))
                    .register(registry)
                    .increment();
        }
    }

    private void countRelay(String outcome, int amount) {
        if (registry != null) {
            Counter.builder(RELAY_METRIC).tag("outcome", outcome).register(registry).increment(amount);
        }
    }

    // A scrape must not fail because the database is down; NaN says « unknown », not « zero ».
    private static double readOrNaN(Supplier<? extends Number> read) {
        try {
            return read.get().doubleValue();
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }
}
