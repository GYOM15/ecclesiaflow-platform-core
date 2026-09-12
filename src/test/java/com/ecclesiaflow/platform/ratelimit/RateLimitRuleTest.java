package com.ecclesiaflow.platform.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A rule is a policy statement; a malformed one must not reach production quietly. */
class RateLimitRuleTest {

    @Test
    @DisplayName("the two factories differ only in what they count")
    void factoriesSetTheScope() {
        assertThat(RateLimitRule.perChurch("a", 1, Duration.ofSeconds(1)).scope())
                .isEqualTo(RateLimitScope.PER_CHURCH);
        assertThat(RateLimitRule.perUser("a", 1, Duration.ofSeconds(1)).scope())
                .isEqualTo(RateLimitScope.PER_USER);
    }

    @Test
    @DisplayName("both default to failing OPEN; failClosed flips only that")
    void defaultsToFailOpen() {
        RateLimitRule open = RateLimitRule.perChurch("a", 5, Duration.ofMinutes(1));
        assertThat(open.failOpen()).isTrue();

        RateLimitRule closed = open.failClosed();
        assertThat(closed.failOpen()).isFalse();
        assertThat(closed.name()).isEqualTo(open.name());
        assertThat(closed.limit()).isEqualTo(open.limit());
        assertThat(closed.window()).isEqualTo(open.window());
        assertThat(closed.scope()).isEqualTo(open.scope());
    }

    @Test
    @DisplayName("a nameless rule is refused — the name IS part of the Redis key")
    void refusesABlankName() {
        assertThatThrownBy(() -> new RateLimitRule(" ", 1, Duration.ofSeconds(1),
                RateLimitScope.PER_CHURCH, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a limit below one would refuse everything, including the first call")
    void refusesAZeroLimit() {
        assertThatThrownBy(() -> new RateLimitRule("a", 0, Duration.ofSeconds(1),
                RateLimitScope.PER_CHURCH, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a zero or negative window would divide by zero at counting time")
    void refusesANonPositiveWindow() {
        assertThatThrownBy(() -> new RateLimitRule("a", 1, Duration.ZERO,
                RateLimitScope.PER_CHURCH, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitRule("a", 1, Duration.ofSeconds(-1),
                RateLimitScope.PER_CHURCH, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a decision never reports a negative remaining, nor a zero wait")
    void decisionsAreClamped() {
        assertThat(RateLimitDecision.allowed(5, -3).remaining()).isZero();
        assertThat(RateLimitDecision.refused(5, 0).retryAfterSeconds()).isEqualTo(1);
    }
}
