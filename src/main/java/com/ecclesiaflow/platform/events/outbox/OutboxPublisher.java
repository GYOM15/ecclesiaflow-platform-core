package com.ecclesiaflow.platform.events.outbox;

/**
 * Stages a domain event in the caller's transaction, so it is published if and only if the
 * business change commits. Delivery is at least once: consumers dedupe by event id. Outside a
 * writable transaction on the outbox DataSource every method throws
 * {@link org.springframework.transaction.IllegalTransactionStateException}: the row would commit on its own.
 *
 * <p>Messages staged under one {@code aggregateKey} are relayed one at a time, in staging order:
 * none leaves while an earlier one of its key awaits a retry or is parked. Stage after the write
 * that locks the entity, so two transactions on it stage in commit order. A {@code null} key
 * relays the message on its own, as soon as it is due.
 */
public interface OutboxPublisher {

    /** Serializes {@code event} with the domain-events template's converter, as {@code convertAndSend} would. */
    default void append(String exchange, String routingKey, Object event) {
        append(exchange, routingKey, event, null);
    }

    void append(String exchange, String routingKey, Object event, String aggregateKey);

    default void append(OutboxMessage message) {
        append(message, null);
    }

    void append(OutboxMessage message, String aggregateKey);
}
