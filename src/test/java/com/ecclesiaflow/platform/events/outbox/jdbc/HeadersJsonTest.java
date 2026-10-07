package com.ecclesiaflow.platform.events.outbox.jdbc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HeadersJsonTest {

    @Test
    @DisplayName("Round-trips text headers, quotes included")
    void roundTrips() {
        Map<String, String> headers = Map.of("__TypeId__", "a.B", "x-note", "it's \"quoted\"");

        assertThat(HeadersJson.read(HeadersJson.write(headers))).isEqualTo(headers);
    }

    @Test
    @DisplayName("Reads a value an operator wrote as a number back as text")
    void coercesScalars() {
        assertThat(HeadersJson.read("{\"x-count\": 3}")).containsEntry("x-count", "3");
    }

    @Test
    @DisplayName("Refuses a column that is not a JSON object")
    void refusesMalformedColumn() {
        assertThatThrownBy(() -> HeadersJson.read("[1, 2]"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("headers");
    }
}
