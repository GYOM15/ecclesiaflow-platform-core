package com.ecclesiaflow.platform.security.reauth.code;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class EmailCodeLimitsTest {

    private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");

    @Test
    @DisplayName("are the closed values of PLAN-REAUTH-CODE §4.4")
    void closedValues() {
        assertThat(EmailCodeLimits.CODE_LIFETIME).isEqualTo(Duration.ofMinutes(10));
        assertThat(EmailCodeLimits.CHALLENGE_LIFETIME).isEqualTo(Duration.ofMinutes(30));
        assertThat(EmailCodeLimits.RESEND_DELAY).isEqualTo(Duration.ofSeconds(60));
        assertThat(EmailCodeLimits.FAILURES_PER_CODE).isEqualTo(5);
        assertThat(EmailCodeLimits.FAILURES_PER_ACCOUNT).isEqualTo(10);
        assertThat(EmailCodeLimits.FAILURE_WINDOW).isEqualTo(Duration.ofHours(24));
        assertThat(EmailCodeLimits.CODES_PER_HOUR).isEqualTo(3);
        assertThat(EmailCodeLimits.CODES_PER_DAY).isEqualTo(10);
        assertThat(EmailCodeLimits.RETENTION).isEqualTo(Duration.ofDays(7));
    }

    @Test
    @DisplayName("allow one more below the limit, else wait for the oldest to leave the window, a second at least")
    void waitBeforeNext() {
        assertThat(EmailCodeLimits.waitBeforeNext(new EmailCodeStore.CounterWindow(2, NOW), 3, Duration.ofHours(1),
                NOW)).isEmpty();
        assertThat(EmailCodeLimits.waitBeforeNext(new EmailCodeStore.CounterWindow(3, NOW.minusSeconds(600)), 3,
                Duration.ofHours(1), NOW)).contains(Duration.ofMinutes(50));
        assertThat(EmailCodeLimits.waitBeforeNext(new EmailCodeStore.CounterWindow(3, NOW.minusSeconds(3600)), 3,
                Duration.ofHours(1), NOW)).contains(Duration.ofSeconds(1));
        assertThat(EmailCodeLimits.waitBeforeNext(new EmailCodeStore.CounterWindow(3, null), 3, Duration.ofHours(1),
                NOW)).contains(Duration.ofHours(1));
    }

    @Test
    @DisplayName("round Retry-After up to the whole second, one at least")
    void retryAfter() {
        assertThat(EmailCodeLimits.retryAfterSeconds(Duration.ofSeconds(15))).isEqualTo(15);
        assertThat(EmailCodeLimits.retryAfterSeconds(Duration.ofMillis(15_001))).isEqualTo(16);
        assertThat(EmailCodeLimits.retryAfterSeconds(Duration.ZERO)).isEqualTo(1);
    }
}
