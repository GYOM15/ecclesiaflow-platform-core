package com.ecclesiaflow.platform.events.outbox.relay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxRetryPolicyTest {

    private final OutboxRetryPolicy policy =
            new OutboxRetryPolicy(10, Duration.ofSeconds(5), 2.0, Duration.ofMinutes(10));

    @Test
    @DisplayName("Backs off exponentially from the initial delay")
    void backsOffExponentially() {
        assertThat(policy.backoffAfter(1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.backoffAfter(2)).isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.backoffAfter(4)).isEqualTo(Duration.ofSeconds(40));
    }

    @Test
    @DisplayName("Caps the delay, even for an attempt count that would overflow")
    void capsTheDelay() {
        assertThat(policy.backoffAfter(9)).isEqualTo(Duration.ofMinutes(10));
        assertThat(policy.backoffAfter(5_000)).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("Treats a non-positive attempt count as the first retry")
    void clampsAttemptCount() {
        assertThat(policy.backoffAfter(0)).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("Is exhausted once the attempts reach the maximum")
    void exhaustion() {
        assertThat(policy.isExhausted(9)).isFalse();
        assertThat(policy.isExhausted(10)).isTrue();
        assertThat(policy.maxAttempts()).isEqualTo(10);
        assertThat(policy.initialBackoff()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("Rejects settings that would retry forever, never, or backwards")
    void rejectsInvalidSettings() {
        assertThatThrownBy(() -> new OutboxRetryPolicy(0, Duration.ofSeconds(5), 2.0, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-attempts");
        assertThatThrownBy(() -> new OutboxRetryPolicy(3, Duration.ZERO, 2.0, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("initial-backoff");
        assertThatThrownBy(() -> new OutboxRetryPolicy(3, null, 2.0, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("initial-backoff");
        assertThatThrownBy(() -> new OutboxRetryPolicy(3, Duration.ofSeconds(5), 0.5, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("backoff-multiplier");
        assertThatThrownBy(() -> new OutboxRetryPolicy(3, Duration.ofSeconds(5), 2.0, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-backoff");
        assertThatThrownBy(() -> new OutboxRetryPolicy(3, Duration.ofSeconds(5), 2.0, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-backoff");
    }
}
