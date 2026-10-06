package com.ecclesiaflow.platform.events.deadletter;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;

import java.util.Collection;
import java.util.List;

/**
 * How many messages wait in each dead-letter queue. Nothing consumes those queues, on purpose: a
 * poison message must not be retried for ever. A queue nobody consumes and nobody measures is a
 * silent drop, and when the dropped message is a revocation, the access it should close stays open.
 *
 * <p>The depth is read from the broker on each scrape, so a purged queue reads zero at once. A broker
 * that cannot be asked, or that does not know the queue, reads -1: "unknown" must not look like
 * "empty" on a dashboard.</p>
 */
public class DeadLetterQueueMetrics implements MeterBinder {

    /** The series the deploy's alert rules watch. */
    public static final String DLQ_DEPTH = "ecclesiaflow_domain_events_dlq_depth";

    static final double UNKNOWN_DEPTH = -1d;

    private final AmqpAdmin amqpAdmin;
    private final List<String> queues;

    /** A {@code null} admin measures nothing. */
    public DeadLetterQueueMetrics(AmqpAdmin amqpAdmin, Collection<String> queues) {
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
            Gauge.builder(DLQ_DEPTH, () -> depthOf(queue))
                    .description("Messages waiting in a domain-event dead-letter queue")
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
