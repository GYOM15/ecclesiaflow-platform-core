package com.ecclesiaflow.platform.events.retry.amqp;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;

import java.util.Collection;
import java.util.List;

/**
 * How many messages wait in each retry stage, read like the dead-letter depth: from the broker on each
 * scrape, -1 when it cannot be asked or does not know the stage. A stage that keeps filling means an
 * external system has been down for longer than its delay.
 */
public class DelayedRetryQueueMetrics implements MeterBinder {

    /** The series beside {@code ecclesiaflow_domain_events_dlq_depth}. */
    public static final String RETRY_DEPTH = "ecclesiaflow_domain_events_retry_depth";

    static final double UNKNOWN_DEPTH = -1d;

    private final AmqpAdmin amqpAdmin;
    private final List<String> queues;

    /** A {@code null} admin measures nothing. */
    public DelayedRetryQueueMetrics(AmqpAdmin amqpAdmin, Collection<String> queues) {
        this.amqpAdmin = amqpAdmin;
        this.queues = List.copyOf(queues);
    }

    public List<String> queues() {
        return queues;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        if (amqpAdmin == null) {
            return;
        }
        for (String queue : queues) {
            Gauge.builder(RETRY_DEPTH, () -> depthOf(queue))
                    .description("Messages waiting in a delayed retry stage")
                    .tag("queue", queue)
                    .register(registry);
        }
    }

    private double depthOf(String queue) {
        try {
            QueueInformation info = amqpAdmin.getQueueInfo(queue);
            return info == null ? UNKNOWN_DEPTH : info.getMessageCount();
        } catch (RuntimeException unreachable) {
            return UNKNOWN_DEPTH;
        }
    }
}
