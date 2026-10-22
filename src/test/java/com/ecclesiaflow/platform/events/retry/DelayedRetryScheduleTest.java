package com.ecclesiaflow.platform.events.retry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DelayedRetryScheduleTest {

    private static final DelayedRetrySchedule STANDARD = DelayedRetrySchedule.STANDARD;

    @Nested
    @DisplayName("The standard schedule")
    class Standard {

        @Test
        @DisplayName("waits 1 min, 5 min, 30 min, then 2 h")
        void waitsTheFourDelaysOfTheArchitecture() {
            assertThat(STANDARD.stages()).isEqualTo(4);
            assertThat(STANDARD.delays()).containsExactly(Duration.ofMinutes(1), Duration.ofMinutes(5),
                    Duration.ofMinutes(30), Duration.ofHours(2));
            assertThat(STANDARD.longestDelay()).isEqualTo(Duration.ofHours(2));
        }

        @Test
        @DisplayName("accepts a redelivery of the longest stage up to 2 h 10 min after it entered the stage")
        void longestWaitIsTwoHoursTenMinutes() {
            assertThat(STANDARD.maxWait(4)).isEqualTo(Duration.ofHours(2).plusMinutes(10));
            assertThat(STANDARD.maxWait(1)).isEqualTo(Duration.ofMinutes(11));
        }

        @Test
        @DisplayName("has stages 1 to 4 only")
        void hasStagesOneToFour() {
            assertThat(STANDARD.hasStage(0)).isFalse();
            assertThat(STANDARD.hasStage(1)).isTrue();
            assertThat(STANDARD.hasStage(4)).isTrue();
            assertThat(STANDARD.hasStage(5)).isFalse();
            assertThatThrownBy(() -> STANDARD.delay(5)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> STANDARD.delay(0)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("A custom schedule")
    class Custom {

        @Test
        @DisplayName("copies its delays, so a caller cannot change them afterwards")
        void copiesItsDelays() {
            List<Duration> delays = new ArrayList<>(List.of(Duration.ofSeconds(1)));
            DelayedRetrySchedule schedule = new DelayedRetrySchedule(delays, Duration.ZERO);
            delays.add(Duration.ofSeconds(2));

            assertThat(schedule.stages()).isEqualTo(1);
        }

        @Test
        @DisplayName("refuses no delay, a non-increasing delay, a fraction of a millisecond or a negative margin")
        void refusesAnUnusableSchedule() {
            assertThatThrownBy(() -> new DelayedRetrySchedule(null, Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new DelayedRetrySchedule(List.of(), Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new DelayedRetrySchedule(List.of(Duration.ZERO), Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new DelayedRetrySchedule(
                    List.of(Duration.ofMinutes(5), Duration.ofMinutes(5)), Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new DelayedRetrySchedule(List.of(Duration.ofNanos(1_500_000)), Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new DelayedRetrySchedule(List.of(Duration.ofDays(30)), Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new DelayedRetrySchedule(List.of(Duration.ofSeconds(1)), null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new DelayedRetrySchedule(List.of(Duration.ofSeconds(1)), Duration.ofSeconds(-1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @ParameterizedTest(name = "{0} ms is labelled {1}")
    @CsvSource({"60000, 1m", "300000, 5m", "1800000, 30m", "7200000, 2h", "5400000, 90m", "1500, 1500ms",
            "45000, 45s", "3600000, 1h"})
    @DisplayName("Labels a delay by the largest unit that divides it")
    void labelsADelay(long millis, String label) {
        assertThat(DelayedRetrySchedule.label(Duration.ofMillis(millis))).isEqualTo(label);
    }
}
