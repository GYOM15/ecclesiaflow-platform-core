package com.ecclesiaflow.platform.security.reauth.code.events;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class EmailCodeMetricsTest {

    private static final UUID CHALLENGE = UUID.randomUUID();

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private double count(String outcome) {
        return registry.get(EmailCodeMetrics.CODES_METRIC).tag("outcome", outcome).counter().count();
    }

    @Test
    @DisplayName("publishes every outcome at zero")
    void zeroAtStart() {
        new EmailCodeMetrics(registry);

        assertThat(EmailCodeMetrics.OUTCOMES).containsExactly("sent", "not_sent", "verified", "invalid", "expired",
                "locked", "superseded");
        EmailCodeMetrics.OUTCOMES.forEach(outcome -> assertThat(count(outcome)).as(outcome).isZero());
    }

    @Test
    @DisplayName("counts each decision under its outcome, a malformed proof as invalid")
    void counts() {
        EmailCodeMetrics metrics = new EmailCodeMetrics(registry);

        metrics.onSent(new EmailCodeEvents.Sent("s", CHALLENGE, "a", false));
        metrics.onNotSent(new EmailCodeEvents.NotSent("s", "a", EmailCodeEvents.NotSentReason.NO_ADDRESS, null));
        metrics.onVerified(new EmailCodeEvents.Verified("s", CHALLENGE, "a", false));
        metrics.onInvalid(new EmailCodeEvents.Invalid("s", CHALLENGE, 1, 1));
        metrics.onMalformed(new EmailCodeEvents.Malformed("s", "a"));
        metrics.onExpired(new EmailCodeEvents.Expired("s", CHALLENGE, EmailCodeEvents.ExpiryReason.LAPSED));
        metrics.onLocked(new EmailCodeEvents.Locked("s", 10));
        metrics.onSuperseded(new EmailCodeEvents.Superseded("s", CHALLENGE, EmailCodeEvents.SupersededBy.NEW_GESTURE));

        assertThat(count("sent")).isEqualTo(1);
        assertThat(count("not_sent")).isEqualTo(1);
        assertThat(count("verified")).isEqualTo(1);
        assertThat(count("invalid")).isEqualTo(2);
        assertThat(count("expired")).isEqualTo(1);
        assertThat(count("locked")).isEqualTo(1);
        assertThat(count("superseded")).isEqualTo(1);
    }

    @Test
    @DisplayName("without a registry counts nothing and never fails a decision")
    void noRegistry() {
        EmailCodeMetrics metrics = new EmailCodeMetrics(null);

        assertThatCode(() -> metrics.onSent(new EmailCodeEvents.Sent("s", CHALLENGE, "a", true)))
                .doesNotThrowAnyException();
    }
}
