package com.ecclesiaflow.platform.events.outbox.relay;

import com.ecclesiaflow.platform.events.outbox.events.OutboxRelayEvents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxRelaySchedulerTest {

    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");
    private static final Duration RETENTION = Duration.ofDays(7);
    private static final RelayBatchResult FULL = new RelayBatchResult(3, 3, 0, 0, 0, true);
    private static final RelayBatchResult PARTIAL = new RelayBatchResult(1, 1, 0, 0, 0, false);

    @Mock
    private OutboxRelay relay;
    @Mock
    private OutboxRepository repository;
    @Mock
    private ApplicationEventPublisher events;

    private OutboxRelayScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = scheduler(Duration.ofHours(1), Duration.ofSeconds(2));
    }

    @AfterEach
    void tearDown() {
        scheduler.stop();
        Thread.interrupted();
    }

    private OutboxRelayScheduler scheduler(Duration pollInterval, Duration shutdownTimeout) {
        return new OutboxRelayScheduler(relay, repository, events, Clock.fixed(NOW, ZoneOffset.UTC),
                new OutboxRelayScheduler.Schedule(pollInterval, Duration.ofHours(1), RETENTION, shutdownTimeout));
    }

    @Nested
    @DisplayName("relay cycle")
    class RelayCycle {

        @Test
        @DisplayName("Keeps relaying while batches come back full")
        void drainsBacklog() {
            when(relay.relayDue()).thenReturn(FULL, FULL, PARTIAL, RelayBatchResult.EMPTY);

            scheduler.relayCycle();

            verify(relay, times(4)).relayDue();
        }

        @Test
        @DisplayName("Keeps relaying while a batch sent something: a burst on one key leaves in one cycle")
        void drainsOneKeyBurst() {
            when(relay.relayDue()).thenReturn(PARTIAL, PARTIAL, PARTIAL, RelayBatchResult.EMPTY);

            scheduler.relayCycle();

            verify(relay, times(4)).relayDue();
        }

        @Test
        @DisplayName("Stops once a partial batch sent nothing: what failed waits for its backoff")
        void stopsWhenNothingSent() {
            when(relay.relayDue()).thenReturn(new RelayBatchResult(1, 0, 1, 0, 0, false));

            scheduler.relayCycle();

            verify(relay, times(1)).relayDue();
        }

        @Test
        @DisplayName("Stops draining once the broker defers messages")
        void stopsWhenDeferred() {
            when(relay.relayDue()).thenReturn(new RelayBatchResult(3, 1, 0, 0, 2, true));

            scheduler.relayCycle();

            verify(relay, times(1)).relayDue();
        }

        @Test
        @DisplayName("Stops draining once a stop is requested")
        void stopsWhenStopping() {
            when(relay.relayDue()).thenAnswer(invocation -> {
                scheduler.stop();
                return FULL;
            });

            scheduler.relayCycle();

            verify(relay, times(1)).relayDue();
        }

        @Test
        @DisplayName("Reports a failed cycle instead of letting it cancel the schedule")
        void reportsFailure() {
            when(relay.relayDue()).thenThrow(new DataAccessResourceFailureException("db down"));

            scheduler.relayCycle();

            verify(events).publishEvent(new OutboxRelayEvents.RelayCycleFailed(
                    OutboxRelayEvents.Task.RELAY, "DataAccessResourceFailureException: db down"));
        }
    }

    @Nested
    @DisplayName("purge cycle")
    class PurgeCycle {

        @Test
        @DisplayName("Deletes relayed rows past retention in chunks, then reports the total")
        void purgesInChunks() {
            Instant cutoff = NOW.minus(RETENTION);
            when(repository.purgeSent(cutoff, OutboxRelayScheduler.PURGE_CHUNK))
                    .thenReturn(OutboxRelayScheduler.PURGE_CHUNK, OutboxRelayScheduler.PURGE_CHUNK, 12);

            scheduler.purgeCycle();

            verify(repository, times(3)).purgeSent(cutoff, OutboxRelayScheduler.PURGE_CHUNK);
            verify(events).publishEvent(new OutboxRelayEvents.SentPurged(
                    2 * OutboxRelayScheduler.PURGE_CHUNK + 12, cutoff));
        }

        @Test
        @DisplayName("Reports nothing when nothing was old enough")
        void purgesNothing() {
            when(repository.purgeSent(NOW.minus(RETENTION), OutboxRelayScheduler.PURGE_CHUNK)).thenReturn(0);

            scheduler.purgeCycle();

            verifyNoInteractions(events);
        }

        @Test
        @DisplayName("Reports a failed purge instead of letting it cancel the schedule")
        void reportsFailure() {
            when(repository.purgeSent(any(), anyInt()))
                    .thenThrow(new DataAccessResourceFailureException("db down"));

            scheduler.purgeCycle();

            verify(events).publishEvent(argThat((Object e) -> e instanceof OutboxRelayEvents.RelayCycleFailed f
                    && f.task() == OutboxRelayEvents.Task.PURGE));
        }
    }

    @Nested
    @DisplayName("lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("Relays on its own thread from start until stop")
        void runsBetweenStartAndStop() {
            scheduler = scheduler(Duration.ofMillis(10), Duration.ofSeconds(2));
            when(relay.relayDue()).thenReturn(RelayBatchResult.EMPTY);

            scheduler.start();
            scheduler.start();

            assertThat(scheduler.isRunning()).isTrue();
            verify(relay, timeout(2_000).atLeast(2)).relayDue();

            scheduler.stop();
            assertThat(scheduler.isRunning()).isFalse();
        }

        @Test
        @DisplayName("Starts with the application and stops after the web server")
        void phase() {
            assertThat(scheduler.isAutoStartup()).isTrue();
            assertThat(scheduler.getPhase()).isLessThan(SmartLifecycle.DEFAULT_PHASE - 2048);
        }

        @Test
        @DisplayName("Interrupts a cycle that outlasts the shutdown timeout")
        void interruptsStuckCycle() throws InterruptedException {
            scheduler = scheduler(Duration.ofMillis(10), Duration.ofMillis(100));
            CountDownLatch inCycle = new CountDownLatch(1);
            AtomicBoolean interrupted = new AtomicBoolean();
            when(relay.relayDue()).thenAnswer(invocation -> {
                inCycle.countDown();
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    interrupted.set(true);
                }
                return PARTIAL;
            });

            scheduler.start();
            assertThat(inCycle.await(2, TimeUnit.SECONDS)).isTrue();
            long started = System.nanoTime();
            scheduler.stop();

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
            verify(relay, timeout(2_000).atLeastOnce()).relayDue();
            assertThat(waitFor(interrupted)).isTrue();
        }

        @Test
        @DisplayName("Stops at once, and stays interrupted, when the stopping thread is interrupted")
        void stopWhileInterrupted() throws InterruptedException {
            scheduler = scheduler(Duration.ofMillis(10), Duration.ofSeconds(30));
            CountDownLatch inCycle = new CountDownLatch(1);
            when(relay.relayDue()).thenAnswer(invocation -> {
                inCycle.countDown();
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return PARTIAL;
            });
            scheduler.start();
            assertThat(inCycle.await(2, TimeUnit.SECONDS)).isTrue();

            Thread.currentThread().interrupt();
            scheduler.stop();

            assertThat(Thread.interrupted()).isTrue();
            assertThat(scheduler.isRunning()).isFalse();
        }

        @Test
        @DisplayName("Does nothing when stopped before it started")
        void stopBeforeStart() {
            scheduler.stop();

            assertThat(scheduler.isRunning()).isFalse();
            verify(relay, never()).relayDue();
        }

        private boolean waitFor(AtomicBoolean flag) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!flag.get() && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(10);
            }
            return flag.get();
        }
    }

    @Nested
    @DisplayName("schedule")
    class ScheduleValidation {

        @Test
        @DisplayName("Rejects a missing or non-positive interval")
        void rejectsInvalidIntervals() {
            Duration ok = Duration.ofSeconds(1);
            assertThatThrownBy(() -> new OutboxRelayScheduler.Schedule(Duration.ZERO, ok, ok, ok))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("poll-interval");
            assertThatThrownBy(() -> new OutboxRelayScheduler.Schedule(ok, null, ok, ok))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("purge-interval");
            assertThatThrownBy(() -> new OutboxRelayScheduler.Schedule(ok, ok, Duration.ofSeconds(-1), ok))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sent-retention");
            assertThatThrownBy(() -> new OutboxRelayScheduler.Schedule(ok, ok, ok, Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("shutdown-timeout");
        }
    }
}
