package com.ecclesiaflow.platform.events.outbox;

/**
 * Stages a domain event in the caller's transaction, so it is published if and only if the
 * business change commits. Delivery is at least once: consumers dedupe by event id. Outside a
 * writable transaction on the outbox DataSource both methods throw
 * {@link org.springframework.transaction.IllegalTransactionStateException}: the row would commit on its own.
 */
public interface OutboxPublisher {

    /** Serializes {@code event} with the domain-events template's converter, as {@code convertAndSend} would. */
    void append(String exchange, String routingKey, Object event);

    void append(OutboxMessage message);
}
