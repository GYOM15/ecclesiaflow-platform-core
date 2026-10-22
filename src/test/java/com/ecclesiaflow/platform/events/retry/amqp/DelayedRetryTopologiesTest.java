package com.ecclesiaflow.platform.events.retry.amqp;

import com.ecclesiaflow.platform.events.retry.DelayedRetryTopology;
import com.ecclesiaflow.platform.events.retry.RetryHop;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DelayedRetryTopologiesTest {

    private static final String QUEUE = "auth.subscriber.member-account-status";
    private static final RetryHop HOP = new RetryHop(QUEUE, 2, 1_790_000_000_000L, "ecclesiaflow.domain-events",
            "members.account.deactivated");

    private final DelayedRetryTopology topology = new DelayedRetryTopology(QUEUE);
    private final DelayedRetryTopologies topologies = new DelayedRetryTopologies(List.of(topology));

    @Test
    @DisplayName("Finds a topology by its consumer queue")
    void findsATopologyByQueue() {
        assertThat(topologies.forQueue(QUEUE)).containsSame(topology);
        assertThat(topologies.forQueue("comm.subscriber.member-account-status")).isEmpty();
        assertThat(topologies.forQueue(null)).isEmpty();
        assertThat(topologies.all()).containsExactly(topology);
        assertThat(topologies.isEmpty()).isFalse();
        assertThat(new DelayedRetryTopologies(List.of()).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("Refuses two topologies for one queue")
    void refusesTwoTopologiesForOneQueue() {
        assertThatThrownBy(() -> new DelayedRetryTopologies(List.of(topology, new DelayedRetryTopology(QUEUE))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(QUEUE);
    }

    @Nested
    @DisplayName("Reads a claimed hop")
    class ClaimedHop {

        @Test
        @DisplayName("from a message that came back to its queue through the default exchange")
        void fromAMessageBackFromARetryStage() {
            assertThat(topologies.claimedHop(message(QUEUE, "", QUEUE, HOP))).contains(HOP);
        }

        @Test
        @DisplayName("never from a message delivered on its published route, whatever headers it carries")
        void neverFromAFirstDelivery() {
            assertThat(topologies.claimedHop(message(QUEUE, "ecclesiaflow.domain-events",
                    "members.account.deactivated", HOP))).isEmpty();
            assertThat(topologies.claimedHop(message(QUEUE, "", "another.queue", HOP))).isEmpty();
        }

        @Test
        @DisplayName("never for a queue without a topology, nor for a hop naming another queue")
        void neverForAnotherQueue() {
            String other = "comm.subscriber.member-account-status";
            RetryHop elsewhere = new RetryHop(other, 1, 0L, "ecclesiaflow.domain-events", "k");

            assertThat(topologies.claimedHop(message(other, "", other, elsewhere))).isEmpty();
            assertThat(topologies.claimedHop(message(QUEUE, "", QUEUE, elsewhere))).isEmpty();
            assertThat(topologies.claimedHop(message(null, "", QUEUE, HOP))).isEmpty();
        }

        @Test
        @DisplayName("never without the hop headers")
        void neverWithoutHeaders() {
            assertThat(topologies.claimedHop(message(QUEUE, "", QUEUE, null))).isEmpty();
        }
    }

    static Message message(String consumerQueue, String exchange, String routingKey, RetryHop hop) {
        MessageProperties properties = new MessageProperties();
        properties.setConsumerQueue(consumerQueue);
        properties.setReceivedExchange(exchange);
        properties.setReceivedRoutingKey(routingKey);
        if (hop != null) {
            hop.headers().forEach(properties::setHeader);
        }
        return new Message(new byte[]{1}, properties);
    }
}
