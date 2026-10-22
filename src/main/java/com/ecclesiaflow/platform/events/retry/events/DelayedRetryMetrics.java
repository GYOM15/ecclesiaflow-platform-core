package com.ecclesiaflow.platform.events.retry.events;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.event.EventListener;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * What the recoverer did with each failed delivery, per consumer queue. The queue tag is bounded: it
 * comes from the container, never from the message.
 */
public class DelayedRetryMetrics {

    /** Exported to Prometheus as {@code ecclesiaflow_domain_events_retry_total}. */
    public static final String RETRY_METRIC = "ecclesiaflow.domain.events.retry";

    static final String SCHEDULED = "scheduled";
    private static final List<String> OUTCOMES = List.of(SCHEDULED,
            outcome(DelayedRetryEvents.DeadLetterCause.EXHAUSTED),
            outcome(DelayedRetryEvents.DeadLetterCause.NOT_RETRYABLE),
            outcome(DelayedRetryEvents.DeadLetterCause.PUBLISH_FAILED));

    private final MeterRegistry registry;

    /** @param registry may be {@code null}: nothing is then counted. */
    public DelayedRetryMetrics(MeterRegistry registry, Collection<String> queues) {
        this.registry = registry;
        if (registry == null) {
            return;
        }
        // Published at zero: increase() cannot see the first increment of a series that starts with it.
        queues.forEach(queue -> OUTCOMES.forEach(outcome -> counter(queue, outcome)));
    }

    @EventListener
    public void onRetryScheduled(DelayedRetryEvents.RetryScheduled event) {
        count(event.queue(), SCHEDULED);
    }

    @EventListener
    public void onDeadLettered(DelayedRetryEvents.DeadLettered event) {
        count(event.queue(), outcome(event.cause()));
    }

    private void count(String queue, String outcome) {
        if (registry != null) {
            counter(queue == null ? "unknown" : queue, outcome).increment();
        }
    }

    private Counter counter(String queue, String outcome) {
        return Counter.builder(RETRY_METRIC)
                .description("Failed deliveries sent to a retry stage or to the dead-letter queue")
                .tag("queue", queue)
                .tag("outcome", outcome)
                .register(registry);
    }

    private static String outcome(DelayedRetryEvents.DeadLetterCause cause) {
        return cause.name().toLowerCase(Locale.ROOT);
    }
}
