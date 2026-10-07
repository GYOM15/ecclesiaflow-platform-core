package com.ecclesiaflow.platform.events.outbox.relay;

import com.ecclesiaflow.platform.events.outbox.events.OutboxRelayEvents;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Owns its thread so the relay runs without {@code @EnableScheduling} and never queues behind the
 * module's jobs; its phase stops it after the web server, once draining requests have staged their rows.
 */
public class OutboxRelayScheduler implements SmartLifecycle {

    static final int PURGE_CHUNK = 500;
    static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 4096;

    private final OutboxRelay relay;
    private final OutboxRepository repository;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final Schedule schedule;

    private ScheduledExecutorService executor;
    private volatile boolean stopRequested;

    public record Schedule(Duration pollInterval, Duration purgeInterval, Duration sentRetention,
                           Duration shutdownTimeout) {

        public Schedule {
            requirePositive("poll-interval", pollInterval);
            requirePositive("purge-interval", purgeInterval);
            requirePositive("sent-retention", sentRetention);
            requirePositive("shutdown-timeout", shutdownTimeout);
        }

        private static void requirePositive(String name, Duration value) {
            if (value == null || value.isNegative() || value.isZero()) {
                throw new IllegalArgumentException("ecclesiaflow.events.outbox." + name + " must be positive");
            }
        }
    }

    public OutboxRelayScheduler(OutboxRelay relay, OutboxRepository repository, ApplicationEventPublisher events,
                                Clock clock, Schedule schedule) {
        this.relay = relay;
        this.repository = repository;
        this.events = events;
        this.clock = clock;
        this.schedule = schedule;
    }

    @Override
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        stopRequested = false;
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "ecclesiaflow-outbox-relay");
            thread.setDaemon(true);
            return thread;
        });
        long poll = schedule.pollInterval().toMillis();
        long purge = schedule.purgeInterval().toMillis();
        executor.scheduleWithFixedDelay(this::relayCycle, poll, poll, TimeUnit.MILLISECONDS);
        executor.scheduleWithFixedDelay(this::purgeCycle, purge, purge, TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        stopRequested = true;
        ScheduledExecutorService stopping;
        synchronized (this) {
            stopping = executor;
            executor = null;
        }
        if (stopping == null) {
            return;
        }
        stopping.shutdown();
        try {
            if (!stopping.awaitTermination(schedule.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                stopping.shutdownNow();
            }
        } catch (InterruptedException e) {
            stopping.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    // A periodic task that throws is never run again, hence the catch-all on both cycles.
    // A claim takes one row per key, so a burst on one key fills no batch: a batch that sent
    // something frees the next row of its key, and the cycle goes on until nothing more leaves.
    void relayCycle() {
        try {
            RelayBatchResult result;
            do {
                result = relay.relayDue();
            } while (!stopRequested && result.deferred() == 0 && (result.batchWasFull() || result.sent() > 0));
        } catch (RuntimeException e) {
            events.publishEvent(new OutboxRelayEvents.RelayCycleFailed(OutboxRelayEvents.Task.RELAY, describe(e)));
        }
    }

    void purgeCycle() {
        try {
            Instant cutoff = clock.instant().minus(schedule.sentRetention());
            int total = 0;
            int deleted;
            do {
                deleted = repository.purgeSent(cutoff, PURGE_CHUNK);
                total += deleted;
            } while (!stopRequested && deleted == PURGE_CHUNK);
            if (total > 0) {
                events.publishEvent(new OutboxRelayEvents.SentPurged(total, cutoff));
            }
        } catch (RuntimeException e) {
            events.publishEvent(new OutboxRelayEvents.RelayCycleFailed(OutboxRelayEvents.Task.PURGE, describe(e)));
        }
    }

    private static String describe(RuntimeException error) {
        return error.getClass().getSimpleName() + ": " + error.getMessage();
    }
}
