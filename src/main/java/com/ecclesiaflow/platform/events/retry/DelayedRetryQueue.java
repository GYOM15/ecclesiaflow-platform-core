package com.ecclesiaflow.platform.events.retry;

import org.springframework.amqp.core.Queue;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One stage of a {@link DelayedRetryTopology}: nothing consumes it, its messages expire after the
 * stage's delay and the broker dead-letters them back to the consumer's queue through the default
 * exchange. The delay is part of the name because RabbitMQ never changes the arguments of a durable
 * queue: another delay is another queue.
 */
public class DelayedRetryQueue extends Queue {

    static final String MESSAGE_TTL = "x-message-ttl";
    static final String DEAD_LETTER_EXCHANGE = "x-dead-letter-exchange";
    static final String DEAD_LETTER_ROUTING_KEY = "x-dead-letter-routing-key";
    static final String DEFAULT_EXCHANGE = "";

    private final String returnQueue;
    private final int attempt;
    private final Duration delay;

    public DelayedRetryQueue(String returnQueue, int attempt, Duration delay) {
        super(nameOf(returnQueue, delay), true, false, false, arguments(returnQueue, delay));
        if (attempt < 1) {
            throw new IllegalArgumentException("Retry stages are counted from 1");
        }
        this.returnQueue = returnQueue;
        this.attempt = attempt;
        this.delay = delay;
    }

    public static String nameOf(String returnQueue, Duration delay) {
        if (returnQueue == null || returnQueue.isBlank()) {
            throw new IllegalArgumentException("A retry stage names the queue its messages return to");
        }
        if (delay == null || delay.toMillis() < 1 || delay.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("A retry stage waits between 1 ms and " + Integer.MAX_VALUE + " ms");
        }
        return returnQueue + ".retry." + DelayedRetrySchedule.label(delay);
    }

    public String returnQueue() {
        return returnQueue;
    }

    public int attempt() {
        return attempt;
    }

    public Duration delay() {
        return delay;
    }

    private static Map<String, Object> arguments(String returnQueue, Duration delay) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put(MESSAGE_TTL, Math.toIntExact(delay.toMillis()));
        arguments.put(DEAD_LETTER_EXCHANGE, DEFAULT_EXCHANGE);
        arguments.put(DEAD_LETTER_ROUTING_KEY, returnQueue);
        return arguments;
    }
}
