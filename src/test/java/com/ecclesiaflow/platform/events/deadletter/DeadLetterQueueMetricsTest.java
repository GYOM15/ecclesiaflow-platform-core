package com.ecclesiaflow.platform.events.deadletter;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeadLetterQueueMetricsTest {

    private static final String REMOVED = "chat.subscriber.member-removed-from-church.dlq";
    private static final String ADMITTED = "chat.subscriber.member-admitted-to-church.dlq";

    @Mock
    private AmqpAdmin amqpAdmin;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private void bind() {
        new DeadLetterQueueMetrics(amqpAdmin, List.of(REMOVED, ADMITTED)).bindTo(registry);
    }

    private double depth(String queue) {
        Gauge gauge = registry.get("ecclesiaflow_domain_events_dlq_depth").tag("queue", queue).gauge();
        return gauge.value();
    }

    @Test
    @DisplayName("Publishes one gauge per dead-letter queue, under the name the deploy alerts on")
    void publishesOneGaugePerQueue() {
        bind();

        assertThat(registry.find(DeadLetterQueueMetrics.DLQ_DEPTH).gauges())
                .extracting(gauge -> gauge.getId().getTag("queue"))
                .containsExactlyInAnyOrder(REMOVED, ADMITTED);
    }

    @Test
    @DisplayName("Reads the broker's own count on each scrape")
    void readsTheBrokersCount() {
        when(amqpAdmin.getQueueInfo(REMOVED))
                .thenReturn(new QueueInformation(REMOVED, 3, 0))
                .thenReturn(new QueueInformation(REMOVED, 0, 0));
        bind();

        assertThat(depth(REMOVED)).isEqualTo(3d);
        assertThat(depth(REMOVED)).isZero();
    }

    @Test
    @DisplayName("An unreachable broker reports -1, never an empty queue")
    void unreachableBrokerIsNotEmpty() {
        when(amqpAdmin.getQueueInfo(anyString())).thenThrow(new AmqpException("broker down"));
        bind();

        assertThat(depth(ADMITTED)).isEqualTo(-1d);
    }

    @Test
    @DisplayName("A queue the broker does not know reports -1 too")
    void unknownQueueIsNotEmpty() {
        when(amqpAdmin.getQueueInfo(ADMITTED)).thenReturn(null);
        bind();

        assertThat(depth(ADMITTED)).isEqualTo(-1d);
    }

    @Test
    @DisplayName("Without a broker admin, nothing is measured")
    void inertWithoutABrokerAdmin() {
        new DeadLetterQueueMetrics(null, List.of(REMOVED)).bindTo(registry);

        assertThat(registry.find(DeadLetterQueueMetrics.DLQ_DEPTH).gauges()).isEmpty();
    }

    @Test
    @DisplayName("Exposes the queues it measures")
    void exposesItsQueues() {
        assertThat(new DeadLetterQueueMetrics(amqpAdmin, List.of(REMOVED, ADMITTED)).queues())
                .containsExactly(REMOVED, ADMITTED);
    }
}
