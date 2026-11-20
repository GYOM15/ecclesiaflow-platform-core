package com.ecclesiaflow.platform.webhooks.processing;

import com.ecclesiaflow.platform.webhooks.WebhookInboxProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InboxRetryPolicyTest {

    private static InboxRetryPolicy defaults() {
        WebhookInboxProperties properties = new WebhookInboxProperties();
        return new InboxRetryPolicy(properties.getMaxAttempts(), properties.getInitialBackoff(),
                properties.getBackoffMultiplier(), properties.getMaxBackoff());
    }

    @Test
    @DisplayName("by default doubles from 30 seconds to an hour and parks at the tenth failure")
    void defaultSchedule() {
        InboxRetryPolicy policy = defaults();

        assertThat(IntStream.rangeClosed(1, 9).mapToObj(policy::backoffAfter)).containsExactly(
                Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(4),
                Duration.ofMinutes(8), Duration.ofMinutes(16), Duration.ofMinutes(32), Duration.ofHours(1),
                Duration.ofHours(1));
        assertThat(policy.isExhausted(9)).isFalse();
        assertThat(policy.isExhausted(10)).isTrue();
        assertThat(policy.maxAttempts()).isEqualTo(10);
    }

    @Test
    @DisplayName("waits the first delay before a retry counted from zero")
    void zeroAttempts() {
        assertThat(defaults().backoffAfter(0)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("refuses settings that would never retry, never wait, shrink or stay below the first delay")
    void refusesNonsense() {
        Duration minute = Duration.ofMinutes(1);
        assertThatThrownBy(() -> new InboxRetryPolicy(0, minute, 2, minute))
                .hasMessageContaining("max-attempts");
        assertThatThrownBy(() -> new InboxRetryPolicy(1, null, 2, minute)).hasMessageContaining("initial-backoff");
        assertThatThrownBy(() -> new InboxRetryPolicy(1, Duration.ZERO, 2, minute))
                .hasMessageContaining("initial-backoff");
        assertThatThrownBy(() -> new InboxRetryPolicy(1, Duration.ofSeconds(-1), 2, minute))
                .hasMessageContaining("initial-backoff");
        assertThatThrownBy(() -> new InboxRetryPolicy(1, minute, 0.5, minute))
                .hasMessageContaining("backoff-multiplier");
        assertThatThrownBy(() -> new InboxRetryPolicy(1, minute, 2, null)).hasMessageContaining("max-backoff");
        assertThatThrownBy(() -> new InboxRetryPolicy(1, minute, 2, Duration.ofSeconds(30)))
                .hasMessageContaining("max-backoff");
    }
}
