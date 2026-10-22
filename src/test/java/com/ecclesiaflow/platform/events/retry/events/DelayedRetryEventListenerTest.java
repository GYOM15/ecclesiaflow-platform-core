package com.ecclesiaflow.platform.events.retry.events;

import nl.altindag.log.LogCaptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class DelayedRetryEventListenerTest {

    private static final String QUEUE = "auth.subscriber.member-account-status";
    private static final String RAW_REASON = "IllegalStateException: no user jane.doe@example.org at http://keycloak:8080/admin";

    private final DelayedRetryEventListener listener = new DelayedRetryEventListener();

    @Test
    @DisplayName("Warns of a scheduled retry with its stage, masking the reason")
    void warnsOfAScheduledRetry() {
        try (LogCaptor logs = LogCaptor.forClass(DelayedRetryEventListener.class)) {
            listener.onRetryScheduled(new DelayedRetryEvents.RetryScheduled(QUEUE, 2, 4, Duration.ofMinutes(5),
                    QUEUE + ".retry.5m", RAW_REASON));

            assertThat(logs.getWarnLogs()).singleElement().asString()
                    .startsWith("EVENT-RETRY: ")
                    .contains(QUEUE, "attempt 2/4", "PT5M", QUEUE + ".retry.5m", "IllegalStateException")
                    .doesNotContain("jane.doe@example.org")
                    .doesNotContain("keycloak:8080");
        }
    }

    @Test
    @DisplayName("Reports a dead-lettered delivery as an error, with its cause, masking the reason")
    void reportsADeadLetteredDelivery() {
        try (LogCaptor logs = LogCaptor.forClass(DelayedRetryEventListener.class)) {
            listener.onDeadLettered(new DelayedRetryEvents.DeadLettered(QUEUE,
                    DelayedRetryEvents.DeadLetterCause.EXHAUSTED, 4, RAW_REASON));

            assertThat(logs.getErrorLogs()).singleElement().asString()
                    .startsWith("EVENT-RETRY: ")
                    .contains(QUEUE, "(exhausted)", "delayed_retries=4")
                    .doesNotContain("jane.doe@example.org")
                    .doesNotContain("keycloak:8080");
        }
    }
}
