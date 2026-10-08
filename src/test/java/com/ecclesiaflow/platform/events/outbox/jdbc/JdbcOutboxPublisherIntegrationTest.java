package com.ecclesiaflow.platform.events.outbox.jdbc;

import com.ecclesiaflow.platform.events.outbox.OutboxMessage;
import com.ecclesiaflow.platform.events.outbox.amqp.AmqpOutboxMessageMapper;
import com.ecclesiaflow.platform.events.outbox.relay.ClaimedOutboxMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class JdbcOutboxPublisherIntegrationTest {

    private static final Instant STAGED_AT = Instant.parse("2026-09-01T10:15:30.123Z");
    private static final String KEY = "chat.group:42:member:7";
    private static final OutboxMessage MESSAGE = new OutboxMessage("ecclesiaflow.domain-events",
            "chat.group.member-admitted.v1", "admitted".getBytes(StandardCharsets.UTF_8), "application/x-protobuf",
            null, "event-1", Map.of("__TypeId__", "com.ecclesiaflow.grpc.events.chat.MemberAdmittedEvent"));

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = OutboxPostgres.container();

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static JdbcOutboxPublisher publisher;
    private static TransactionTemplate business;

    @BeforeAll
    static void createTable() {
        dataSource = OutboxPostgres.dataSource(POSTGRES);
        OutboxPostgres.createTable(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        publisher = new JdbcOutboxPublisher(new JdbcTemplate(dataSource),
                new AmqpOutboxMessageMapper(new SimpleMessageConverter()), Clock.fixed(STAGED_AT, ZoneOffset.UTC));
        business = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void emptyTable() {
        jdbc.execute("TRUNCATE outbox_event RESTART IDENTITY");
    }

    private static long rows() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM outbox_event", Long.class);
        return count == null ? 0 : count;
    }

    @Nested
    @DisplayName("inside the business transaction")
    class InsideTransaction {

        @Test
        @DisplayName("Commits a pending row, due at once, under its ordering key")
        void commitsPendingRow() {
            business.executeWithoutResult(status -> publisher.append(MESSAGE, KEY));

            Map<String, Object> row = jdbc.queryForMap("""
                    SELECT status, attempts, aggregate_key, next_attempt_at, created_at, sent_at, parked_at,
                           headers ->> '__TypeId__' AS type_id
                    FROM outbox_event""");
            assertThat(row)
                    .containsEntry("status", "PENDING")
                    .containsEntry("attempts", 0)
                    .containsEntry("aggregate_key", KEY)
                    .containsEntry("sent_at", null)
                    .containsEntry("parked_at", null)
                    .containsEntry("type_id", "com.ecclesiaflow.grpc.events.chat.MemberAdmittedEvent");
            assertThat(((Timestamp) row.get("next_attempt_at")).toInstant()).isEqualTo(STAGED_AT);
            assertThat(((Timestamp) row.get("created_at")).toInstant()).isEqualTo(STAGED_AT);
        }

        @Test
        @DisplayName("Stores no key for a message staged without one")
        void storesNullKey() {
            business.executeWithoutResult(status -> publisher.append(MESSAGE));

            assertThat(jdbc.queryForObject("SELECT aggregate_key FROM outbox_event", String.class)).isNull();
        }

        @Test
        @DisplayName("Hands the relay back exactly the message staged")
        void roundTripsThroughTheClaim() {
            business.executeWithoutResult(status -> publisher.append(MESSAGE, KEY));
            JdbcOutboxRepository repository = new JdbcOutboxRepository(jdbc, OutboxPostgres.requiresNew(dataSource));

            List<ClaimedOutboxMessage> claimed = repository.claimDue(STAGED_AT, STAGED_AT.plusSeconds(120), 10);

            assertThat(claimed).singleElement().satisfies(row -> {
                assertThat(row.attempts()).isZero();
                assertThat(row.message()).isEqualTo(MESSAGE);
            });
        }

        @Test
        @DisplayName("Stages a converted event in the same transaction")
        void stagesConvertedEvent() {
            business.executeWithoutResult(status ->
                    publisher.append("ecclesiaflow.domain-events", "chat.group.member-removed.v1", "removed", KEY));

            assertThat(jdbc.queryForMap("SELECT routing_key, content_type, payload FROM outbox_event"))
                    .containsEntry("routing_key", "chat.group.member-removed.v1")
                    .containsEntry("content_type", "text/plain")
                    .hasEntrySatisfying("payload", payload ->
                            assertThat((byte[]) payload).isEqualTo("removed".getBytes(StandardCharsets.UTF_8)));
        }

        @Test
        @DisplayName("Leaves no row behind when the business transaction rolls back")
        void rollsBackWithTheBusinessChange() {
            business.executeWithoutResult(status -> {
                publisher.append(MESSAGE, KEY);
                status.setRollbackOnly();
            });

            assertThat(rows()).isZero();
        }
    }

    @Nested
    @DisplayName("outside the business transaction")
    class OutsideTransaction {

        @Test
        @DisplayName("Refuses to write when no transaction is active, and writes nothing")
        void refusesWithoutTransaction() {
            assertThatThrownBy(() -> publisher.append(MESSAGE, KEY))
                    .isInstanceOf(IllegalTransactionStateException.class);

            assertThat(rows()).isZero();
        }

        @Test
        @DisplayName("Refuses to write in a read-only transaction")
        void refusesReadOnlyTransaction() {
            TransactionTemplate readOnly = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            readOnly.setReadOnly(true);

            assertThatThrownBy(() -> readOnly.executeWithoutResult(status -> publisher.append(MESSAGE, KEY)))
                    .isInstanceOf(IllegalTransactionStateException.class);

            assertThat(rows()).isZero();
        }

        @Test
        @DisplayName("Refuses to write when the transaction runs on another DataSource")
        void refusesTransactionOnAnotherDataSource() {
            TransactionTemplate otherUnit = new TransactionTemplate(
                    new DataSourceTransactionManager(OutboxPostgres.dataSource(POSTGRES)));

            assertThatThrownBy(() -> otherUnit.executeWithoutResult(status -> publisher.append(MESSAGE, KEY)))
                    .isInstanceOf(IllegalTransactionStateException.class);

            assertThat(rows()).isZero();
        }
    }

    @Nested
    @DisplayName("discardDelivered")
    class DiscardDelivered {

        @BeforeEach
        void relayedRow() {
            business.executeWithoutResult(status -> publisher.append(MESSAGE, KEY));
            jdbc.update("UPDATE outbox_event SET status = 'SENT', sent_at = now()");
        }

        @Test
        @DisplayName("Deletes in the business transaction, next to the events it stages")
        void commitsWithTheBusinessChange() {
            business.executeWithoutResult(status -> {
                publisher.discardDelivered(KEY);
                publisher.append(MESSAGE, KEY);
            });

            assertThat(jdbc.queryForList("SELECT status FROM outbox_event", String.class)).containsExactly("PENDING");
        }

        @Test
        @DisplayName("Keeps the rows when the business transaction rolls back")
        void rollsBackWithTheBusinessChange() {
            business.executeWithoutResult(status -> {
                publisher.discardDelivered(KEY);
                status.setRollbackOnly();
            });

            assertThat(rows()).isEqualTo(1);
        }

        @Test
        @DisplayName("Refuses to delete when no transaction is active, and deletes nothing")
        void refusesWithoutTransaction() {
            assertThatThrownBy(() -> publisher.discardDelivered(KEY))
                    .isInstanceOf(IllegalTransactionStateException.class);

            assertThat(rows()).isEqualTo(1);
        }
    }
}
