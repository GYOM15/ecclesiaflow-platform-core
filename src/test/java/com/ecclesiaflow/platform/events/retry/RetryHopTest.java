package com.ecclesiaflow.platform.events.retry;

import com.rabbitmq.client.impl.LongStringHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryHopTest {

    private static final RetryHop HOP = new RetryHop("auth.subscriber.member-account-status", 2, 1_790_000_000_000L,
            "ecclesiaflow.domain-events", "members.account.deactivated");

    @Test
    @DisplayName("Writes every hop header as text and reads it back")
    void roundTripsThroughHeaders() {
        Map<String, Object> headers = new HashMap<>(HOP.headers());

        assertThat(HOP.headers()).containsOnlyKeys(RetryHop.QUEUE_HEADER, RetryHop.ATTEMPT_HEADER,
                RetryHop.RETRIED_AT_HEADER, RetryHop.ORIGINAL_EXCHANGE_HEADER, RetryHop.ORIGINAL_ROUTING_KEY_HEADER);
        assertThat(RetryHop.fromHeaders(headers)).contains(HOP);
    }

    @Test
    @DisplayName("Reads headers the AMQP client hands over as long strings")
    void readsLongStrings() {
        Map<String, Object> headers = new HashMap<>();
        HOP.headers().forEach((name, value) -> headers.put(name, LongStringHelper.asLongString(value)));

        assertThat(RetryHop.fromHeaders(headers)).contains(HOP);
    }

    @ParameterizedTest(name = "without {0}")
    @ValueSource(strings = {RetryHop.QUEUE_HEADER, RetryHop.ATTEMPT_HEADER, RetryHop.RETRIED_AT_HEADER,
            RetryHop.ORIGINAL_EXCHANGE_HEADER, RetryHop.ORIGINAL_ROUTING_KEY_HEADER})
    @DisplayName("Reads no hop when a header is missing")
    void readsNoHopWithoutEveryHeader(String missing) {
        Map<String, Object> headers = new HashMap<>(HOP.headers());
        headers.remove(missing);

        assertThat(RetryHop.fromHeaders(headers)).isEmpty();
    }

    @Test
    @DisplayName("Reads no hop from malformed numbers, a blank queue, a stage 0 or no headers at all")
    void readsNoHopFromMalformedHeaders() {
        assertThat(RetryHop.fromHeaders(null)).isEmpty();
        assertThat(RetryHop.fromHeaders(with(RetryHop.ATTEMPT_HEADER, "two"))).isEmpty();
        assertThat(RetryHop.fromHeaders(with(RetryHop.RETRIED_AT_HEADER, "yesterday"))).isEmpty();
        assertThat(RetryHop.fromHeaders(with(RetryHop.ATTEMPT_HEADER, "0"))).isEmpty();
        assertThat(RetryHop.fromHeaders(with(RetryHop.QUEUE_HEADER, " "))).isEmpty();
    }

    @Test
    @DisplayName("Refuses a hop without a queue, a positive attempt or the original route")
    void refusesAnIncompleteHop() {
        assertThatThrownBy(() -> new RetryHop(null, 1, 0, "x", "k")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryHop("q", 0, 0, "x", "k")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryHop("q", 1, 0, null, "k")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryHop("q", 1, 0, "x", null)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Map<String, Object> with(String name, String value) {
        Map<String, Object> headers = new HashMap<>(HOP.headers());
        headers.put(name, value);
        return headers;
    }
}
