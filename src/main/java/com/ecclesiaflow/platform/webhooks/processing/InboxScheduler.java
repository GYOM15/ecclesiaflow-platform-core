package com.ecclesiaflow.platform.webhooks.processing;

import com.ecclesiaflow.platform.webhooks.events.WebhookInboxEvents;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Owns its thread so the inbox is handled without {@code @EnableScheduling} or a scheduler lock: the claim's lease
 * already keeps two instances off one notification. Its phase stops it after the web server, once the requests
 * in flight have recorded theirs.
 */
public class InboxScheduler implements SmartLifecycle {

    static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 4096;

    private final InboxProcessor processor;
    private final ApplicationEventPublisher events;
    private final Duration pollInterval;
    private final Duration shutdownTimeout;

    private ScheduledExecutorService executor;
    private volatile boolean stopRequested;

    public InboxScheduler(InboxProcessor processor, ApplicationEventPublisher events, Duration pollInterval,
                          Duration shutdownTimeout) {
        if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("ecclesiaflow.webhooks.inbox.poll-interval must be positive");
        }
        if (shutdownTimeout == null || shutdownTimeout.isNegative() || shutdownTimeout.isZero()) {
            throw new IllegalArgumentException("The inbox's shutdown timeout must be positive");
        }
        this.processor = processor;
        this.events = events;
        this.pollInterval = pollInterval;
        this.shutdownTimeout = shutdownTimeout;
    }

    @Override
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        stopRequested = false;
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "ecclesiaflow-webhook-inbox");
            thread.setDaemon(true);
            return thread;
        });
        long poll = pollInterval.toMillis();
        executor.scheduleWithFixedDelay(this::processCycle, poll, poll, TimeUnit.MILLISECONDS);
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
            if (!stopping.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
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

    // A periodic task that throws is never run again, hence the catch-all.
    void processCycle() {
        try {
            ProcessingBatchResult result;
            do {
                result = processor.processDue();
            } while (!stopRequested && result.batchWasFull());
        } catch (RuntimeException e) {
            events.publishEvent(new WebhookInboxEvents.ProcessingCycleFailed(
                    e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }
}
