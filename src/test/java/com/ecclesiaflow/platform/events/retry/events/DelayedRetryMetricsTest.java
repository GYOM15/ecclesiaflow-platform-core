package com.ecclesiaflow.platform.events.retry.events;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class DelayedRetryMetricsTest {

    private static final String QUEUE = "auth.subscriber.member-account-status";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final DelayedRetryMetrics metrics = new DelayedRetryMetrics(registry, List.of(QUEUE));

    private double counted(String queue, String outcome) {
        Counter counter = registry.find(DelayedRetryMetrics.RETRY_METRIC).tag("queue", queue).tag("outcome", outcome)
                .counter();
        return counter == null ? Double.NaN : counter.count();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"scheduled", "exhausted", "not_retryable", "publish_failed"})
    @DisplayName("Publishes every outcome of an opted-in queue at zero")
    void startsAtZero(String outcome) {
        assertThat(counted(QUEUE, outcome)).isZero();
    }

    @Test
    @DisplayName("Counts scheduled retries and dead-lettered deliveries by queue and outcome")
    void countsOutcomes() {
        metrics.onRetryScheduled(new DelayedRetryEvents.RetryScheduled(QUEUE, 1, 4, Duration.ofMinutes(1), "r", "x"));
        metrics.onRetryScheduled(new DelayedRetryEvents.RetryScheduled(QUEUE, 2, 4, Duration.ofMinutes(5), "r", "x"));
        metrics.onDeadLettered(new DelayedRetryEvents.DeadLettered(QUEUE,
                DelayedRetryEvents.DeadLetterCause.PUBLISH_FAILED, 2, "x"));
        metrics.onDeadLettered(new DelayedRetryEvents.DeadLettered("comm.subscriber.setup-token-issued",
                DelayedRetryEvents.DeadLetterCause.EXHAUSTED, 0, "x"));
        metrics.onDeadLettered(new DelayedRetryEvents.DeadLettered(null,
                DelayedRetryEvents.DeadLetterCause.NOT_RETRYABLE, 0, "x"));

        assertThat(counted(QUEUE, "scheduled")).isEqualTo(2d);
        assertThat(counted(QUEUE, "publish_failed")).isEqualTo(1d);
        assertThat(counted("comm.subscriber.setup-token-issued", "exhausted")).isEqualTo(1d);
        assertThat(counted("unknown", "not_retryable")).isEqualTo(1d);
    }

    @Test
    @DisplayName("Counts nothing, and does not fail, without a registry")
    void inertWithoutRegistry() {
        DelayedRetryMetrics inert = new DelayedRetryMetrics(null, List.of(QUEUE));

        assertThatCode(() -> {
            inert.onRetryScheduled(new DelayedRetryEvents.RetryScheduled(QUEUE, 1, 4, Duration.ofMinutes(1), "r", "x"));
            inert.onDeadLettered(new DelayedRetryEvents.DeadLettered(QUEUE,
                    DelayedRetryEvents.DeadLetterCause.EXHAUSTED, 4, "x"));
        }).doesNotThrowAnyException();
    }
}
