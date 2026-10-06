package com.ecclesiaflow.platform.events.outbox.events;

import com.ecclesiaflow.platform.events.outbox.relay.OutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxRelayMetricsTest {

    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");

    @Mock
    private OutboxRepository repository;

    private SimpleMeterRegistry registry;
    private OutboxRelayMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new OutboxRelayMetrics(registry, repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private double relayed(String outcome) {
        return registry.get(OutboxRelayMetrics.RELAY_METRIC).tag("outcome", outcome).counter().count();
    }

    @Nested
    @DisplayName("counters")
    class Counters {

        @ParameterizedTest
        @ValueSource(strings = {"sent", "retried", "parked", "deferred"})
        @DisplayName("Publishes each relay outcome at zero before anything happens, so increase() sees the first one")
        void outcomesStartAtZero(String outcome) {
            assertThat(registry.find(OutboxRelayMetrics.RELAY_METRIC).tag("outcome", outcome).counter())
                    .isNotNull()
                    .extracting(Counter::count).isEqualTo(0d);
        }

        @ParameterizedTest
        @EnumSource(OutboxRelayEvents.Task.class)
        @DisplayName("Publishes each task's failed cycles at zero before any failure")
        void cycleFailuresStartAtZero(OutboxRelayEvents.Task task) {
            assertThat(registry.find(OutboxRelayMetrics.CYCLE_FAILURE_METRIC)
                    .tag("task", task.name().toLowerCase(Locale.ROOT)).counter())
                    .isNotNull()
                    .extracting(Counter::count).isEqualTo(0d);
        }

        @Test
        @DisplayName("Keeps the series bounded to the known outcomes and tasks")
        void boundedSeries() {
            assertThat(registry.find(OutboxRelayMetrics.RELAY_METRIC).counters()).hasSize(4);
            assertThat(registry.find(OutboxRelayMetrics.CYCLE_FAILURE_METRIC).counters())
                    .hasSize(OutboxRelayEvents.Task.values().length);
        }

        @Test
        @DisplayName("Counts each relay outcome under one metric")
        void countsOutcomes() {
            metrics.onMessageRelayed(new OutboxRelayEvents.MessageRelayed(1, "ex", "rk", 1));
            metrics.onMessageRelayed(new OutboxRelayEvents.MessageRelayed(2, "ex", "rk", 1));
            metrics.onRetryScheduled(new OutboxRelayEvents.RetryScheduled(3, "ex", "rk", 1, 10, NOW, "nack"));
            metrics.onMessageParked(new OutboxRelayEvents.MessageParked(4, "ex", "rk", 10, "unroutable"));
            metrics.onMessagesDeferred(new OutboxRelayEvents.MessagesDeferred(5, NOW, "refused"));

            assertThat(relayed("sent")).isEqualTo(2);
            assertThat(relayed("retried")).isEqualTo(1);
            assertThat(relayed("parked")).isEqualTo(1);
            assertThat(relayed("deferred")).isEqualTo(5);
        }

        @Test
        @DisplayName("Counts failed cycles by task")
        void countsCycleFailures() {
            metrics.onRelayCycleFailed(new OutboxRelayEvents.RelayCycleFailed(OutboxRelayEvents.Task.PURGE, "x"));

            assertThat(registry.get(OutboxRelayMetrics.CYCLE_FAILURE_METRIC).tag("task", "purge").counter().count())
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("gauges")
    class Gauges {

        @Test
        @DisplayName("Reads the backlog, the parked rows and the age of the oldest pending row")
        void readsTheTable() {
            when(repository.countPending()).thenReturn(4L);
            when(repository.countParked()).thenReturn(2L);
            when(repository.oldestPendingCreatedAt()).thenReturn(Optional.of(NOW.minusSeconds(90)));

            assertThat(registry.get(OutboxRelayMetrics.PENDING_METRIC).gauge().value()).isEqualTo(4);
            assertThat(registry.get(OutboxRelayMetrics.PARKED_METRIC).gauge().value()).isEqualTo(2);
            assertThat(registry.get(OutboxRelayMetrics.OLDEST_PENDING_AGE_METRIC).gauge().value()).isEqualTo(90);
        }

        @Test
        @DisplayName("Reports an age of zero when nothing is pending")
        void zeroAgeWhenEmpty() {
            when(repository.oldestPendingCreatedAt()).thenReturn(Optional.empty());

            assertThat(registry.get(OutboxRelayMetrics.OLDEST_PENDING_AGE_METRIC).gauge().value()).isZero();
        }

        @Test
        @DisplayName("Reports no value, rather than a wrong one, when the table cannot be read")
        void noValueWhenUnreadable() {
            when(repository.countPending()).thenThrow(new DataAccessResourceFailureException("down"));
            when(repository.countParked()).thenThrow(new DataAccessResourceFailureException("down"));
            when(repository.oldestPendingCreatedAt()).thenThrow(new DataAccessResourceFailureException("down"));

            assertThat(registry.get(OutboxRelayMetrics.PENDING_METRIC).gauge().value()).isNaN();
            assertThat(registry.get(OutboxRelayMetrics.PARKED_METRIC).gauge().value()).isNaN();
            assertThat(registry.get(OutboxRelayMetrics.OLDEST_PENDING_AGE_METRIC).gauge().value()).isNaN();
        }
    }

    @Test
    @DisplayName("Counts nothing and reads nothing without a registry")
    void inertWithoutRegistry() {
        OutboxRelayMetrics withoutRegistry =
                new OutboxRelayMetrics(null, repository, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatCode(() -> {
            withoutRegistry.onMessageRelayed(new OutboxRelayEvents.MessageRelayed(1, "ex", "rk", 1));
            withoutRegistry.onRetryScheduled(new OutboxRelayEvents.RetryScheduled(1, "ex", "rk", 1, 10, NOW, "x"));
            withoutRegistry.onMessageParked(new OutboxRelayEvents.MessageParked(1, "ex", "rk", 10, "x"));
            withoutRegistry.onMessagesDeferred(new OutboxRelayEvents.MessagesDeferred(1, NOW, "x"));
            withoutRegistry.onRelayCycleFailed(
                    new OutboxRelayEvents.RelayCycleFailed(OutboxRelayEvents.Task.RELAY, "x"));
        }).doesNotThrowAnyException();
        verifyNoInteractions(repository);
    }
}
