package com.ecclesiaflow.platform.security.reauth.events;

import com.ecclesiaflow.platform.security.reauth.ReauthenticationRequiredException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class RecentAuthMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private double count(String reason) {
        return registry.get(RecentAuthMetrics.REFUSALS_METRIC).tag("reason", reason).counter().count();
    }

    @Test
    @DisplayName("publishes every reason at zero, so the first refusal is seen by increase()")
    void zeroAtStart() {
        new RecentAuthMetrics(registry);

        for (ReauthenticationRequiredException.Reason reason : ReauthenticationRequiredException.Reason.values()) {
            assertThat(count(reason.name())).as(reason.name()).isZero();
        }
    }

    @Test
    @DisplayName("counts each refusal under its reason")
    void counts() {
        RecentAuthMetrics metrics = new RecentAuthMetrics(registry);

        metrics.onRefused(new RecentAuthEvents.Refused("s", ReauthenticationRequiredException.Reason
                .SECOND_FACTOR_NOT_PRESENTED, 400L, 300));
        metrics.onRefused(new RecentAuthEvents.Refused("s", ReauthenticationRequiredException.Reason
                .SECOND_FACTOR_NOT_PRESENTED, null, 300));
        metrics.onRefused(new RecentAuthEvents.Refused("s", ReauthenticationRequiredException.Reason
                .SIGN_IN_TOO_OLD, 400L, 300));

        assertThat(count("SECOND_FACTOR_NOT_PRESENTED")).isEqualTo(2);
        assertThat(count("SIGN_IN_TOO_OLD")).isEqualTo(1);
        assertThat(count("NO_USER_TOKEN")).isZero();
    }

    @Test
    @DisplayName("without a registry counts nothing and never fails the refusal")
    void noRegistry() {
        RecentAuthMetrics metrics = new RecentAuthMetrics(null);

        assertThatCode(() -> metrics.onRefused(new RecentAuthEvents.Refused("s",
                ReauthenticationRequiredException.Reason.NO_USER_TOKEN, null, 300))).doesNotThrowAnyException();
    }
}
