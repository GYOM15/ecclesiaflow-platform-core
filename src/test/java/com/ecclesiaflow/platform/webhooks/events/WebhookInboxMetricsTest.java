package com.ecclesiaflow.platform.webhooks.events;

import com.ecclesiaflow.platform.webhooks.InboundNotification;
import com.ecclesiaflow.platform.webhooks.InvalidNotificationException;
import com.ecclesiaflow.platform.webhooks.NotificationReceipt;
import com.ecclesiaflow.platform.webhooks.NotificationVerifier;
import com.ecclesiaflow.platform.webhooks.processing.InboxRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookInboxMetricsTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    private static final NotificationVerifier ACCOUNTS = new NotificationVerifier() {
        @Override
        public String provider() {
            return "acme";
        }

        @Override
        public String endpoint() {
            return "accounts";
        }

        @Override
        public InboundNotification verify(byte[] body, Map<String, String> headers) {
            throw InvalidNotificationException.badSignature(null);
        }
    };

    @Mock
    private InboxRepository repository;

    private SimpleMeterRegistry registry;
    private WebhookInboxMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new WebhookInboxMetrics(registry, repository, List.of(ACCOUNTS), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private double received(String outcome) {
        return registry.get(WebhookInboxMetrics.RECEIVED_METRIC).tag("provider", "acme").tag("endpoint", "accounts")
                .tag("outcome", outcome).counter().count();
    }

    private double handled(String outcome) {
        return registry.get(WebhookInboxMetrics.HANDLED_METRIC).tag("provider", "acme").tag("outcome", outcome)
                .counter().count();
    }

    @Test
    @DisplayName("publishes every series of each declared endpoint at zero, so the first event shows")
    void publishedAtZero() {
        assertThat(List.of("recorded", "duplicate", "refused_mode", "unmatched", WebhookInboxMetrics.REJECTED))
                .allSatisfy(outcome -> assertThat(received(outcome)).isZero());
        assertThat(List.of("handled", "retried", "parked"))
                .allSatisfy(outcome -> assertThat(handled(outcome)).isZero());
        assertThat(registry.get(WebhookInboxMetrics.CYCLE_FAILURE_METRIC).counter().count()).isZero();
    }

    @Test
    @DisplayName("counts what each endpoint received and what the handler made of it")
    void counts() {
        metrics.onNotificationReceived(new WebhookInboxEvents.NotificationReceived("acme", "accounts", "t",
                NotificationReceipt.RECORDED, 1L));
        metrics.onNotificationReceived(new WebhookInboxEvents.NotificationReceived("acme", "accounts", "t",
                NotificationReceipt.UNMATCHED, 2L));
        metrics.onNotificationRejected(new WebhookInboxEvents.NotificationRejected("acme", "accounts",
                InvalidNotificationException.Reason.SIGNATURE));
        metrics.onNotificationHandled(new WebhookInboxEvents.NotificationHandled(1, "acme", "t", "APPLIED", 1));
        metrics.onRetryScheduled(new WebhookInboxEvents.RetryScheduled(1, "acme", "t", 1, 10, NOW, "x"));
        metrics.onNotificationParked(new WebhookInboxEvents.NotificationParked(1, "acme", "t", 10, "x"));
        metrics.onProcessingCycleFailed(new WebhookInboxEvents.ProcessingCycleFailed("db down"));

        assertThat(received("recorded")).isEqualTo(1);
        assertThat(received("unmatched")).isEqualTo(1);
        assertThat(received(WebhookInboxMetrics.REJECTED)).isEqualTo(1);
        assertThat(handled("handled")).isEqualTo(1);
        assertThat(handled("retried")).isEqualTo(1);
        assertThat(handled("parked")).isEqualTo(1);
        assertThat(registry.get(WebhookInboxMetrics.CYCLE_FAILURE_METRIC).counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("reads the backlog, the set-aside notifications and the oldest pending age on each scrape")
    void gauges() {
        when(repository.countPending()).thenReturn(4L);
        when(repository.countParked()).thenReturn(2L);
        when(repository.countUnmatched()).thenReturn(1L);
        when(repository.oldestPendingReceivedAt()).thenReturn(Optional.of(NOW.minusSeconds(90)), Optional.empty());

        assertThat(registry.get(WebhookInboxMetrics.PENDING_METRIC).gauge().value()).isEqualTo(4);
        assertThat(registry.get(WebhookInboxMetrics.PARKED_METRIC).gauge().value()).isEqualTo(2);
        assertThat(registry.get(WebhookInboxMetrics.UNMATCHED_METRIC).gauge().value()).isEqualTo(1);
        assertThat(registry.get(WebhookInboxMetrics.OLDEST_PENDING_AGE_METRIC).gauge().value()).isEqualTo(90);
        assertThat(registry.get(WebhookInboxMetrics.OLDEST_PENDING_AGE_METRIC).gauge().value()).isZero();
    }

    @Test
    @DisplayName("says « unknown » rather than zero while the database cannot be read")
    void unknownWhileTheDatabaseIsDown() {
        when(repository.countPending()).thenThrow(new DataAccessResourceFailureException("db down"));

        assertThat(registry.get(WebhookInboxMetrics.PENDING_METRIC).gauge().value()).isNaN();
    }

    @Test
    @DisplayName("without a registry counts nothing and reads nothing")
    void inertWithoutARegistry() {
        WebhookInboxMetrics inert = new WebhookInboxMetrics(null, repository, List.of(ACCOUNTS), Clock.systemUTC());

        assertThatCode(() -> {
            inert.onNotificationReceived(new WebhookInboxEvents.NotificationReceived("acme", "accounts", "t",
                    NotificationReceipt.RECORDED, 1L));
            inert.onNotificationRejected(new WebhookInboxEvents.NotificationRejected("acme", "accounts",
                    InvalidNotificationException.Reason.MALFORMED));
            inert.onNotificationHandled(new WebhookInboxEvents.NotificationHandled(1, "acme", "t", "APPLIED", 1));
            inert.onProcessingCycleFailed(new WebhookInboxEvents.ProcessingCycleFailed("db down"));
        }).doesNotThrowAnyException();
        verifyNoInteractions(repository);
    }
}
