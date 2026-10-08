package com.ecclesiaflow.platform.events.outbox.jdbc;

import com.ecclesiaflow.platform.events.outbox.OutboxMessage;
import com.ecclesiaflow.platform.events.outbox.OutboxPublisher;
import com.ecclesiaflow.platform.events.outbox.amqp.AmqpOutboxMessageMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Clock;

/**
 * The JdbcTemplate joins the connection a JPA or JDBC transaction manager holds on the module's
 * DataSource, so the row commits or rolls back with the business change.
 */
public class JdbcOutboxPublisher implements OutboxPublisher {

    static final String INSERT_SQL = """
            INSERT INTO outbox_event
                (exchange, routing_key, payload, content_type, content_encoding, message_id, headers,
                 aggregate_key, status, attempts, next_attempt_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, 'PENDING', 0, ?, ?)""";

    static final String DISCARD_DELIVERED_SQL =
            "DELETE FROM outbox_event WHERE aggregate_key = ? AND status IN ('SENT', 'PARKED')";

    private final JdbcTemplate jdbcTemplate;
    private final AmqpOutboxMessageMapper mapper;
    private final Clock clock;

    public JdbcOutboxPublisher(JdbcTemplate jdbcTemplate, AmqpOutboxMessageMapper mapper, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public void append(String exchange, String routingKey, Object event, String aggregateKey) {
        requireWritableTransaction();
        requireUsableKey(aggregateKey);
        insert(mapper.toOutboxMessage(exchange, routingKey, event), aggregateKey);
    }

    @Override
    public void append(OutboxMessage message, String aggregateKey) {
        requireWritableTransaction();
        requireUsableKey(aggregateKey);
        insert(message, aggregateKey);
    }

    @Override
    public void discardDelivered(String aggregateKey) {
        requireWritableTransaction();
        if (aggregateKey == null || aggregateKey.isBlank()) {
            throw new IllegalArgumentException("Discarding outbox rows needs the ordering key they were staged under");
        }
        jdbcTemplate.update(DISCARD_DELIVERED_SQL, aggregateKey);
    }

    private void insert(OutboxMessage message, String aggregateKey) {
        Timestamp now = Timestamp.from(clock.instant());
        jdbcTemplate.update(INSERT_SQL, message.exchange(), message.routingKey(), message.payload(),
                message.contentType(), message.contentEncoding(), message.messageId(),
                HeadersJson.write(message.headers()), aggregateKey, now, now);
    }

    // A blank key is almost always an unset id; it would queue unrelated messages behind each other.
    private static void requireUsableKey(String aggregateKey) {
        if (aggregateKey != null && aggregateKey.isBlank()) {
            throw new IllegalArgumentException("An outbox ordering key must not be blank; pass null for none");
        }
    }

    private void requireWritableTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalTransactionStateException(
                    "The outbox is written inside the business transaction; no transaction is active");
        }
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalTransactionStateException("The outbox cannot be written in a read-only transaction");
        }
        // A transaction managed on another DataSource (church's finance unit, say) would leave
        // this write on a separate auto-committed connection.
        if (!TransactionSynchronizationManager.hasResource(jdbcTemplate.getDataSource())) {
            throw new IllegalTransactionStateException("The active transaction holds no connection of the outbox "
                    + "DataSource; the outbox write would commit on its own");
        }
    }
}
