package com.ecclesiaflow.platform.events.retry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

class DelayedRetryTopologyTest {

    private static final String QUEUE = "auth.subscriber.member-account-status";

    @Nested
    @DisplayName("With the standard schedule")
    class Standard {

        private final DelayedRetryTopology topology = new DelayedRetryTopology(QUEUE);

        @Test
        @DisplayName("declares one durable queue per stage, named by its delay")
        void declaresOneQueuePerStage() {
            assertThat(topology.getDeclarablesByType(Queue.class)).extracting(Queue::getName).containsExactly(
                    QUEUE + ".retry.1m", QUEUE + ".retry.5m", QUEUE + ".retry.30m", QUEUE + ".retry.2h");
            assertThat(topology.getDeclarablesByType(Queue.class))
                    .allSatisfy(queue -> {
                        assertThat(queue.isDurable()).isTrue();
                        assertThat(queue.isExclusive()).isFalse();
                        assertThat(queue.isAutoDelete()).isFalse();
                    });
        }

        @Test
        @DisplayName("sends each stage's expired messages back to the consumer queue through the default exchange")
        void expiresBackToTheConsumerQueue() {
            assertThat(topology.retryQueue(3).getArguments()).containsExactly(
                    entry("x-message-ttl", 1_800_000),
                    entry("x-dead-letter-exchange", ""),
                    entry("x-dead-letter-routing-key", QUEUE));
        }

        @Test
        @DisplayName("knows each stage's attempt, delay and consumer queue")
        void describesEachStage() {
            DelayedRetryQueue last = topology.retryQueue(4);

            assertThat(topology.queue()).isEqualTo(QUEUE);
            assertThat(topology.schedule()).isEqualTo(DelayedRetrySchedule.STANDARD);
            assertThat(topology.retryQueues()).hasSize(4).last().isSameAs(last);
            assertThat(last.attempt()).isEqualTo(4);
            assertThat(last.delay()).isEqualTo(Duration.ofHours(2));
            assertThat(last.returnQueue()).isEqualTo(QUEUE);
        }

        @Test
        @DisplayName("has no stage 0 nor a stage past the last")
        void refusesAStageOutsideTheSchedule() {
            assertThatThrownBy(() -> topology.retryQueue(0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> topology.retryQueue(5)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("Follows a custom schedule")
    void followsACustomSchedule() {
        DelayedRetryTopology topology = new DelayedRetryTopology(QUEUE,
                new DelayedRetrySchedule(List.of(Duration.ofSeconds(2), Duration.ofSeconds(4)), Duration.ofSeconds(1)));

        assertThat(topology.retryQueues()).extracting(Queue::getName)
                .containsExactly(QUEUE + ".retry.2s", QUEUE + ".retry.4s");
        assertThat(topology.retryQueue(1).getArguments()).containsEntry("x-message-ttl", 2000);
    }

    @Test
    @DisplayName("Refuses a topology without a queue or a schedule")
    void refusesAnIncompleteTopology() {
        assertThatThrownBy(() -> new DelayedRetryTopology(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DelayedRetryTopology(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DelayedRetryTopology(QUEUE, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Refuses a stage without a consumer queue, a usable delay or a positive attempt")
    void refusesAnUnusableStage() {
        assertThatThrownBy(() -> new DelayedRetryQueue(null, 1, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DelayedRetryQueue(QUEUE, 1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DelayedRetryQueue(QUEUE, 1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DelayedRetryQueue(QUEUE, 1, Duration.ofDays(30)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DelayedRetryQueue(QUEUE, 0, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
