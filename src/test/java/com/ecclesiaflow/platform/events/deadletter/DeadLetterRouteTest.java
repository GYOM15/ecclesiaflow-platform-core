package com.ecclesiaflow.platform.events.deadletter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DeadLetterRoute")
class DeadLetterRouteTest {

    @Test
    @DisplayName("pairs a subscription queue with its dead-letter queue")
    void pairsTheQueues() {
        DeadLetterRoute route = new DeadLetterRoute("comm.subscriber.setup-token-issued",
                "comm.subscriber.setup-token-issued.dlq");

        assertThat(route.queue()).isEqualTo("comm.subscriber.setup-token-issued");
        assertThat(route.deadLetterQueue()).isEqualTo("comm.subscriber.setup-token-issued.dlq");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    @DisplayName("refuses a blank queue on either side")
    void refusesBlankNames(String name) {
        assertThatThrownBy(() -> new DeadLetterRoute(name, "q.dlq")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeadLetterRoute("q", name)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("refuses a queue that would dead-letter into itself")
    void refusesALoop() {
        assertThatThrownBy(() -> new DeadLetterRoute("q", "q"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("q");
    }
}
