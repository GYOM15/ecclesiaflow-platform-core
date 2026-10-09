package com.ecclesiaflow.platform.events.deadletter;

/**
 * A subscription queue and the queue that keeps what its listener rejects, for a subscription declared
 * without {@code x-dead-letter-*} arguments: RabbitMQ never changes the arguments of a durable queue,
 * whereas a policy changes in place. Declared as a bean, it gets the dead-letter queue measured, and
 * {@link DeadLetterQueues#policies} turns it into the policy the broker needs.
 */
public record DeadLetterRoute(String queue, String deadLetterQueue) {

    public DeadLetterRoute {
        if (queue == null || queue.isBlank() || deadLetterQueue == null || deadLetterQueue.isBlank()) {
            throw new IllegalArgumentException("A dead-letter route names both of its queues");
        }
        if (queue.equals(deadLetterQueue)) {
            throw new IllegalArgumentException("Queue '" + queue + "' cannot dead-letter into itself");
        }
    }
}
