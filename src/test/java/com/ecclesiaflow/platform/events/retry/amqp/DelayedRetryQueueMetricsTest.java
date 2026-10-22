package com.ecclesiaflow.platform.events.retry.amqp;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DelayedRetryQueueMetricsTest {

    private static final String STAGE = "auth.subscriber.member-account-status.retry.30m";

    private final AmqpAdmin admin = mock(AmqpAdmin.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private double depth() {
        return registry.get(DelayedRetryQueueMetrics.RETRY_DEPTH).tag("queue", STAGE).gauge().value();
    }

    @Test
    @DisplayName("Reads each stage's depth from the broker on every scrape")
    void readsTheBrokerDepth() {
        new DelayedRetryQueueMetrics(admin, List.of(STAGE)).bindTo(registry);
        when(admin.getQueueInfo(STAGE)).thenReturn(new QueueInformation(STAGE, 7, 0));

        assertThat(depth()).isEqualTo(7d);
    }

    @Test
    @DisplayName("Reads -1, not zero, when the broker cannot be asked or does not know the stage")
    void unknownIsNotEmpty() {
        new DelayedRetryQueueMetrics(admin, List.of(STAGE)).bindTo(registry);

        when(admin.getQueueInfo(STAGE)).thenReturn(null);
        assertThat(depth()).isEqualTo(DelayedRetryQueueMetrics.UNKNOWN_DEPTH);

        when(admin.getQueueInfo(STAGE)).thenThrow(new AmqpConnectException(new java.net.ConnectException("down")));
        assertThat(depth()).isEqualTo(DelayedRetryQueueMetrics.UNKNOWN_DEPTH);
    }

    @Test
    @DisplayName("Measures nothing without an admin")
    void inertWithoutAdmin() {
        DelayedRetryQueueMetrics metrics = new DelayedRetryQueueMetrics(null, List.of(STAGE));
        metrics.bindTo(registry);

        assertThat(metrics.queues()).containsExactly(STAGE);
        assertThat(registry.find(DelayedRetryQueueMetrics.RETRY_DEPTH).gauges()).isEmpty();
    }
}
