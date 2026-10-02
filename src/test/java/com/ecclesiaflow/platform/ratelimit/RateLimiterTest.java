package com.ecclesiaflow.platform.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** The port's own contract, whatever counts behind it. */
class RateLimiterTest {

    private static final RateLimitRule RULE =
            RateLimitRule.perChurch("import", 200, Duration.ofHours(1));

    private final RateLimiter limiter = mock(RateLimiter.class, CALLS_REAL_METHODS);

    @Test
    @DisplayName("a batch is never collapsed into a single-unit call")
    void aCostIsNeverDropped() {
        limiter.consume(RULE, "church-1", 900);

        verify(limiter, never()).consume(RULE, "church-1");
    }

    @Test
    @DisplayName("a single call is a batch of one")
    void aSingleCallCostsOne() {
        limiter.consume(RULE, "church-1");

        verify(limiter).consume(RULE, "church-1", 1);
    }
}
