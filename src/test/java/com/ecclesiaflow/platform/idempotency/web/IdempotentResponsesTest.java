package com.ecclesiaflow.platform.idempotency.web;

import com.ecclesiaflow.platform.idempotency.CreationOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotentResponsesTest {

    @Test
    @DisplayName("a creation keeps the operation's status and carries no replay header")
    void created() {
        ResponseEntity<String> response = IdempotentResponses.created(CreationOutcome.created(7), id -> "comment-" + id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().containsKey(IdempotencyHeaders.REPLAYED)).isFalse();
        assertThat(response.getBody()).isEqualTo("comment-7");
    }

    @Test
    @DisplayName("a replay is 200 with Idempotent-Replayed: true and the stored resource")
    void replayed() {
        ResponseEntity<String> response = IdempotentResponses.created(CreationOutcome.replay(7), id -> "comment-" + id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("idempotent-replayed")).isEqualTo("true");
        assertThat(response.getBody()).isEqualTo("comment-7");
    }

    @Test
    @DisplayName("an operation created with another status, or a Location, completes the builder itself")
    void builder() {
        URI location = URI.create("/messages/7");

        ResponseEntity<String> created = IdempotentResponses.status(CreationOutcome.created(7), HttpStatus.OK)
                .location(location).body("message");
        ResponseEntity<String> replayed = IdempotentResponses.status(CreationOutcome.replay(7), HttpStatus.ACCEPTED)
                .location(location).body("message");

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(created.getHeaders().containsKey(IdempotencyHeaders.REPLAYED)).isFalse();
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayed.getHeaders().getFirst(IdempotencyHeaders.REPLAYED)).isEqualTo("true");
        assertThat(replayed.getHeaders().getFirst(HttpHeaders.LOCATION)).isEqualTo("/messages/7");
    }
}
