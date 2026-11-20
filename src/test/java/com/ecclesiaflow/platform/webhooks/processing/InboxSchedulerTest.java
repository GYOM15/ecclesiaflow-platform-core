package com.ecclesiaflow.platform.webhooks.processing;

import com.ecclesiaflow.platform.webhooks.events.WebhookInboxEvents;
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

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InboxSchedulerTest {

    private static final ProcessingBatchResult FULL = new ProcessingBatchResult(3, 3, 0, 0, true);
    private static final ProcessingBatchResult PARTIAL = new ProcessingBatchResult(1, 1, 0, 0, false);

    @Mock
    private InboxProcessor processor;
    @Mock
    private ApplicationEventPublisher events;

    private InboxScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new InboxScheduler(processor, events, Duration.ofHours(1), Duration.ofSeconds(2));
    }

    @AfterEach
    void tearDown() {
        scheduler.stop();
        Thread.interrupted();
    }

    @Nested
    @DisplayName("processing cycle")
    class Cycle {

        @Test
        @DisplayName("keeps processing while batches come back full")
        void drainsBacklog() {
            when(processor.processDue()).thenReturn(FULL, FULL, PARTIAL);

            scheduler.processCycle();

            verify(processor, times(3)).processDue();
        }

        @Test
        @DisplayName("stops draining once a stop is requested")
        void stopsWhenStopping() {
            when(processor.processDue()).thenAnswer(invocation -> {
                scheduler.stop();
                return FULL;
            });

            scheduler.processCycle();

            verify(processor, times(1)).processDue();
        }

        @Test
        @DisplayName("reports a failed run instead of letting it cancel the schedule")
        void reportsFailure() {
            when(processor.processDue()).thenThrow(new DataAccessResourceFailureException("db down"));

            scheduler.processCycle();

            verify(events).publishEvent(
                    new WebhookInboxEvents.ProcessingCycleFailed("DataAccessResourceFailureException: db down"));
        }
    }

    @Nested
    @DisplayName("lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("processes on its own thread from start until stop")
        void runsBetweenStartAndStop() {
            scheduler = new InboxScheduler(processor, events, Duration.ofMillis(10), Duration.ofSeconds(2));
            when(processor.processDue()).thenReturn(ProcessingBatchResult.EMPTY);

            scheduler.start();
            scheduler.start();

            assertThat(scheduler.isRunning()).isTrue();
            verify(processor, timeout(2_000).atLeast(2)).processDue();

            scheduler.stop();
            assertThat(scheduler.isRunning()).isFalse();
            scheduler.stop();
        }

        @Test
        @DisplayName("starts with the application and stops after the web server")
        void phase() {
            assertThat(scheduler.isAutoStartup()).isTrue();
            assertThat(scheduler.getPhase()).isLessThan(SmartLifecycle.DEFAULT_PHASE - 2048);
        }

        @Test
        @DisplayName("interrupts a run that outlasts the shutdown grace")
        void interruptsStuckRun() throws InterruptedException {
            scheduler = new InboxScheduler(processor, events, Duration.ofMillis(10), Duration.ofMillis(100));
            CountDownLatch inRun = new CountDownLatch(1);
            AtomicBoolean interrupted = new AtomicBoolean();
            when(processor.processDue()).thenAnswer(invocation -> {
                inRun.countDown();
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    interrupted.set(true);
                }
                return PARTIAL;
            });

            scheduler.start();
            assertThat(inRun.await(2, TimeUnit.SECONDS)).isTrue();
            scheduler.stop();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!interrupted.get() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(interrupted).isTrue();
        }

        @Test
        @DisplayName("stops at once, and stays interrupted, when the stopping thread is interrupted")
        void stopWhileInterrupted() throws InterruptedException {
            scheduler = new InboxScheduler(processor, events, Duration.ofMillis(10), Duration.ofSeconds(30));
            CountDownLatch inRun = new CountDownLatch(1);
            when(processor.processDue()).thenAnswer(invocation -> {
                inRun.countDown();
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return PARTIAL;
            });
            scheduler.start();
            assertThat(inRun.await(2, TimeUnit.SECONDS)).isTrue();

            Thread.currentThread().interrupt();
            scheduler.stop();

            assertThat(Thread.interrupted()).isTrue();
            assertThat(scheduler.isRunning()).isFalse();
        }
    }

    @Test
    @DisplayName("refuses a poll interval or a shutdown grace that is not positive")
    void refusesNonsense() {
        assertThatThrownBy(() -> new InboxScheduler(processor, events, Duration.ZERO, Duration.ofSeconds(1)))
                .hasMessageContaining("poll-interval");
        assertThatThrownBy(() -> new InboxScheduler(processor, events, null, Duration.ofSeconds(1)))
                .hasMessageContaining("poll-interval");
        assertThatThrownBy(() -> new InboxScheduler(processor, events, Duration.ofSeconds(-1), Duration.ofSeconds(1)))
                .hasMessageContaining("poll-interval");
        assertThatThrownBy(() -> new InboxScheduler(processor, events, Duration.ofSeconds(1), Duration.ZERO))
                .hasMessageContaining("shutdown");
        assertThatThrownBy(() -> new InboxScheduler(processor, events, Duration.ofSeconds(1), null))
                .hasMessageContaining("shutdown");
        assertThatThrownBy(() -> new InboxScheduler(processor, events, Duration.ofSeconds(1), Duration.ofSeconds(-1)))
                .hasMessageContaining("shutdown");
    }
}
