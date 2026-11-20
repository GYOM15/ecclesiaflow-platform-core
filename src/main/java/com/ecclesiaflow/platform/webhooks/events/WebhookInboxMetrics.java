package com.ecclesiaflow.platform.webhooks.events;

import com.ecclesiaflow.platform.webhooks.NotificationReceipt;
import com.ecclesiaflow.platform.webhooks.NotificationVerifier;
import com.ecclesiaflow.platform.webhooks.processing.InboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.event.EventListener;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Alert on rejected bodies (forgeries, or a rotated secret not deployed), on unmatched and parked notifications,
 * and on the oldest pending age, which grows whether the handler keeps failing or the processor thread is gone.
 * Tags are the provider and the endpoint the module's verifiers declare, so the series stay bounded.
 */
public class WebhookInboxMetrics {

    public static final String RECEIVED_METRIC = "ecclesiaflow.webhooks.received";
    public static final String HANDLED_METRIC = "ecclesiaflow.webhooks.handled";
    public static final String CYCLE_FAILURE_METRIC = "ecclesiaflow.webhooks.processing.cycle.failures";
    public static final String PENDING_METRIC = "ecclesiaflow.webhooks.inbox.pending";
    public static final String PARKED_METRIC = "ecclesiaflow.webhooks.inbox.parked";
    public static final String UNMATCHED_METRIC = "ecclesiaflow.webhooks.inbox.unmatched";
    public static final String OLDEST_PENDING_AGE_METRIC = "ecclesiaflow.webhooks.inbox.oldest.pending.age";

    static final String REJECTED = "rejected";
    private static final String HANDLED = "handled";
    private static final String RETRIED = "retried";
    private static final String PARKED = "parked";

    private final MeterRegistry registry;

    /** @param registry may be {@code null}: nothing is then counted nor read. */
    public WebhookInboxMetrics(MeterRegistry registry, InboxRepository repository, List<NotificationVerifier> verifiers,
                               Clock clock) {
        this.registry = registry;
        if (registry == null) {
            return;
        }
        Gauge.builder(PENDING_METRIC, () -> readOrNaN(repository::countPending))
                .description("Provider notifications waiting to be handled")
                .register(registry);
        Gauge.builder(PARKED_METRIC, () -> readOrNaN(repository::countParked))
                .description("Provider notifications set aside after exhausting their attempts")
                .register(registry);
        Gauge.builder(UNMATCHED_METRIC, () -> readOrNaN(repository::countUnmatched))
                .description("Provider notifications about an account the module does not know")
                .register(registry);
        Gauge.builder(OLDEST_PENDING_AGE_METRIC, () -> readOrNaN(() -> repository.oldestPendingReceivedAt()
                        .map(oldest -> Duration.between(oldest, clock.instant()).toSeconds())
                        .orElse(0L)))
                .description("Age of the oldest provider notification waiting to be handled")
                .baseUnit("seconds")
                .register(registry);
        // Published at zero: increase() cannot see the first increment of a series that starts with it.
        for (NotificationVerifier verifier : verifiers) {
            for (NotificationReceipt receipt : NotificationReceipt.values()) {
                receivedCounter(verifier.provider(), verifier.endpoint(), outcomeOf(receipt));
            }
            receivedCounter(verifier.provider(), verifier.endpoint(), REJECTED);
            for (String outcome : List.of(HANDLED, RETRIED, PARKED)) {
                handledCounter(verifier.provider(), outcome);
            }
        }
        Counter.builder(CYCLE_FAILURE_METRIC).register(registry);
    }

    @EventListener
    public void onNotificationReceived(WebhookInboxEvents.NotificationReceived event) {
        if (registry != null) {
            receivedCounter(event.provider(), event.endpoint(), outcomeOf(event.receipt())).increment();
        }
    }

    @EventListener
    public void onNotificationRejected(WebhookInboxEvents.NotificationRejected event) {
        if (registry != null) {
            receivedCounter(event.provider(), event.endpoint(), REJECTED).increment();
        }
    }

    @EventListener
    public void onNotificationHandled(WebhookInboxEvents.NotificationHandled event) {
        countHandled(event.provider(), HANDLED);
    }

    @EventListener
    public void onRetryScheduled(WebhookInboxEvents.RetryScheduled event) {
        countHandled(event.provider(), RETRIED);
    }

    @EventListener
    public void onNotificationParked(WebhookInboxEvents.NotificationParked event) {
        countHandled(event.provider(), PARKED);
    }

    @EventListener
    public void onProcessingCycleFailed(WebhookInboxEvents.ProcessingCycleFailed event) {
        if (registry != null) {
            Counter.builder(CYCLE_FAILURE_METRIC).register(registry).increment();
        }
    }

    private void countHandled(String provider, String outcome) {
        if (registry != null) {
            handledCounter(provider, outcome).increment();
        }
    }

    private Counter receivedCounter(String provider, String endpoint, String outcome) {
        return Counter.builder(RECEIVED_METRIC)
                .tag("provider", provider)
                .tag("endpoint", endpoint)
                .tag("outcome", outcome)
                .register(registry);
    }

    private Counter handledCounter(String provider, String outcome) {
        return Counter.builder(HANDLED_METRIC).tag("provider", provider).tag("outcome", outcome).register(registry);
    }

    private static String outcomeOf(NotificationReceipt receipt) {
        return receipt.name().toLowerCase(Locale.ROOT);
    }

    // A scrape must not fail because the database is down; NaN says « unknown », not « zero ».
    private static double readOrNaN(Supplier<? extends Number> read) {
        try {
            return read.get().doubleValue();
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }
}
