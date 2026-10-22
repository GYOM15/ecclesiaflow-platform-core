package com.ecclesiaflow.platform.events.retry;

import java.time.Duration;
import java.util.List;

/**
 * The delays a failed delivery waits before each new attempt, and the margin a redelivery gets on top
 * of its delay before the verifier calls it stale. Attempts are counted from 1, as the stages.
 */
public record DelayedRetrySchedule(List<Duration> delays, Duration margin) {

    /** 1 min, 5 min, 30 min, 2 h, then the dead-letter queue; the longest wait accepted is 2 h 10 min. */
    public static final DelayedRetrySchedule STANDARD = new DelayedRetrySchedule(
            List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2)),
            Duration.ofMinutes(10));

    // Declared as an int x-message-ttl, as QueueBuilder.ttl does.
    private static final long LONGEST_DELAY_MILLIS = Integer.MAX_VALUE;

    /** Strictly increasing whole milliseconds: each stage gets a queue of its own, named by its delay. */
    public DelayedRetrySchedule {
        if (delays == null || delays.isEmpty()) {
            throw new IllegalArgumentException("A delayed retry schedule has at least one delay");
        }
        delays = List.copyOf(delays);
        Duration previous = Duration.ZERO;
        for (Duration delay : delays) {
            if (delay.compareTo(previous) <= 0) {
                throw new IllegalArgumentException("Retry delays must be positive and strictly increasing: " + delays);
            }
            if (!delay.equals(Duration.ofMillis(delay.toMillis())) || delay.toMillis() > LONGEST_DELAY_MILLIS) {
                throw new IllegalArgumentException("A retry delay is a whole number of milliseconds up to "
                        + LONGEST_DELAY_MILLIS + ": " + delay);
            }
            previous = delay;
        }
        if (margin == null || margin.isNegative()) {
            throw new IllegalArgumentException("The redelivery margin must not be negative");
        }
    }

    public int stages() {
        return delays.size();
    }

    public boolean hasStage(int attempt) {
        return attempt >= 1 && attempt <= delays.size();
    }

    public Duration delay(int attempt) {
        if (!hasStage(attempt)) {
            throw new IllegalArgumentException("No retry stage " + attempt + " in a schedule of " + stages());
        }
        return delays.get(attempt - 1);
    }

    public Duration longestDelay() {
        return delays.get(delays.size() - 1);
    }

    /** How long after it entered the queue of stage {@code attempt} a redelivery may still be consumed. */
    public Duration maxWait(int attempt) {
        return delay(attempt).plus(margin);
    }

    /** Distinct for distinct delays: the largest unit that divides the delay names it. */
    static String label(Duration delay) {
        long millis = delay.toMillis();
        if (millis % Duration.ofHours(1).toMillis() == 0) {
            return millis / Duration.ofHours(1).toMillis() + "h";
        }
        if (millis % Duration.ofMinutes(1).toMillis() == 0) {
            return millis / Duration.ofMinutes(1).toMillis() + "m";
        }
        if (millis % Duration.ofSeconds(1).toMillis() == 0) {
            return millis / Duration.ofSeconds(1).toMillis() + "s";
        }
        return millis + "ms";
    }
}
