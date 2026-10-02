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
                 status, attempts, next_attempt_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), 'PENDING', 0, ?, ?)""";

    private final JdbcTemplate jdbcTemplate;
    private final AmqpOutboxMessageMapper mapper;
    private final Clock clock;

    public JdbcOutboxPublisher(JdbcTemplate jdbcTemplate, AmqpOutboxMessageMapper mapper, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public void append(String exchange, String routingKey, Object event) {
        requireWritableTransaction();
        append(mapper.toOutboxMessage(exchange, routingKey, event));
    }

    @Override
    public void append(OutboxMessage message) {
        requireWritableTransaction();
        Timestamp now = Timestamp.from(clock.instant());
        jdbcTemplate.update(INSERT_SQL, message.exchange(), message.routingKey(), message.payload(),
                message.contentType(), message.contentEncoding(), message.messageId(),
                HeadersJson.write(message.headers()), now, now);
    }

    private void requireWritableTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalTransactionStateException(
                    "An outbox message must be appended inside the business transaction; no transaction is active");
        }
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalTransactionStateException("An outbox message cannot be appended in a read-only transaction");
        }
        // A transaction managed on another DataSource (church's finance unit, say) would leave
        // this insert on a separate auto-committed connection.
        if (!TransactionSynchronizationManager.hasResource(jdbcTemplate.getDataSource())) {
            throw new IllegalTransactionStateException("The active transaction holds no connection of the outbox "
                    + "DataSource; the outbox row would commit on its own");
        }
    }
}
