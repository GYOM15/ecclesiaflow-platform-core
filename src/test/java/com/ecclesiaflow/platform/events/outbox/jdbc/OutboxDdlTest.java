package com.ecclesiaflow.platform.events.outbox.jdbc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxDdlTest {

    @Test
    @DisplayName("Ships the table every adapter statement relies on")
    void declaresEveryColumnTheAdaptersUse() {
        List<String> statements = List.of(
                JdbcOutboxPublisher.INSERT_SQL,
                JdbcOutboxRepository.CLAIM_SQL,
                JdbcOutboxRepository.MARK_SENT_SQL,
                JdbcOutboxRepository.RESCHEDULE_SQL,
                JdbcOutboxRepository.PARK_SQL,
                JdbcOutboxRepository.PURGE_SQL,
                JdbcOutboxRepository.COUNT_BY_STATUS_SQL,
                JdbcOutboxRepository.OLDEST_PENDING_SQL);

        for (String sql : statements) {
            assertThat(OutboxDdl.columns()).as(sql).containsAll(OutboxDdl.columnsIn(sql));
        }
        assertThat(OutboxDdl.columns()).containsExactly(
                "id", "exchange", "routing_key", "payload", "content_type", "content_encoding", "message_id",
                "headers", "aggregate_key", "status", "attempts", "next_attempt_at", "last_error", "created_at",
                "sent_at", "parked_at");
    }

    @Test
    @DisplayName("Constrains the status to the three states the relay writes")
    void constrainsStatus() {
        assertThat(OutboxDdl.text()).contains("CHECK (status IN ('PENDING', 'SENT', 'PARKED'))");
    }

    @Test
    @DisplayName("Indexes due rows, relayed rows and parked rows separately")
    void indexesEachScan() {
        assertThat(OutboxDdl.text())
                .contains("ON outbox_event (next_attempt_at, id) WHERE status = 'PENDING'")
                .contains("ON outbox_event (sent_at) WHERE status = 'SENT'")
                .contains("ON outbox_event (parked_at) WHERE status = 'PARKED'");
    }

    @Test
    @DisplayName("Indexes each key's unsent rows under the very predicate the claim filters on")
    void indexesUnsentRowsByKey() {
        // A partial index serves the claim's subquery only if the planner can prove its predicate.
        String unsent = "status IN ('PENDING', 'PARKED')";

        assertThat(OutboxDdl.text()).contains("ON outbox_event (aggregate_key, id) WHERE " + unsent);
        assertThat(JdbcOutboxRepository.CLAIM_SQL).contains("older." + unsent);
    }

    @Test
    @DisplayName("Documents how to replay or drop a parked row, on the table's own columns")
    void documentsParkedRowStatements() {
        String replay = OutboxDdl.documentedStatement("UPDATE outbox_event");
        String drop = OutboxDdl.documentedStatement("DELETE FROM outbox_event");

        assertThat(replay).contains("SET status = 'PENDING'").endsWith("WHERE status = 'PARKED' AND id = ?");
        assertThat(drop).isEqualTo("DELETE FROM outbox_event WHERE status = 'PARKED' AND id = ?");
        assertThat(OutboxDdl.columns()).containsAll(OutboxDdl.columnsIn(replay)).containsAll(OutboxDdl.columnsIn(drop));
    }

    @Test
    @DisplayName("Is not a Flyway migration of the library itself")
    void isNotOnTheFlywayPath() {
        assertThat(OutboxDdl.RESOURCE).doesNotStartWith("/db/migration");
    }
}
