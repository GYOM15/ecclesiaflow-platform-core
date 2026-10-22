package com.ecclesiaflow.platform.events.retry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Where a delivery stands in its consumer's delayed retries: the stage it entered, when, and the route
 * it was first published on, which its signature is bound to. The consumer's recoverer writes it in
 * headers; read back, it is only a claim until {@link DelayedRetryStamp} vouches for it.
 */
public record RetryHop(String queue, int attempt, long retriedAt, String originalExchange,
                       String originalRoutingKey) {

    public static final String QUEUE_HEADER = "x-ef-retry-queue";
    public static final String ATTEMPT_HEADER = "x-ef-retry-attempt";
    /** Epoch milliseconds at which the copy entered its stage. */
    public static final String RETRIED_AT_HEADER = "x-ef-retried-at";
    public static final String ORIGINAL_EXCHANGE_HEADER = "x-ef-original-exchange";
    public static final String ORIGINAL_ROUTING_KEY_HEADER = "x-ef-original-routing-key";
    /** {@link DelayedRetryStamp}'s HMAC over the hop, the event's signature and its body. */
    public static final String STAMP_HEADER = "x-ef-retry-stamp";

    public RetryHop {
        if (queue == null || queue.isBlank()) {
            throw new IllegalArgumentException("A retry hop names the queue it returns to");
        }
        if (attempt < 1) {
            throw new IllegalArgumentException("Retry attempts are counted from 1");
        }
        if (originalExchange == null || originalRoutingKey == null) {
            throw new IllegalArgumentException("A retry hop keeps the route the event was signed for");
        }
    }

    /** Every header but the stamp, as strings: a header's text is what the stamp covers. */
    public Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(QUEUE_HEADER, queue);
        headers.put(ATTEMPT_HEADER, Integer.toString(attempt));
        headers.put(RETRIED_AT_HEADER, Long.toString(retriedAt));
        headers.put(ORIGINAL_EXCHANGE_HEADER, originalExchange);
        headers.put(ORIGINAL_ROUTING_KEY_HEADER, originalRoutingKey);
        return headers;
    }

    /** Empty unless every hop header is present and well formed; the stamp is not checked here. */
    public static Optional<RetryHop> fromHeaders(Map<String, Object> headers) {
        if (headers == null) {
            return Optional.empty();
        }
        String queue = text(headers.get(QUEUE_HEADER));
        String attempt = text(headers.get(ATTEMPT_HEADER));
        String retriedAt = text(headers.get(RETRIED_AT_HEADER));
        String exchange = text(headers.get(ORIGINAL_EXCHANGE_HEADER));
        String routingKey = text(headers.get(ORIGINAL_ROUTING_KEY_HEADER));
        if (queue == null || queue.isBlank() || attempt == null || retriedAt == null || exchange == null
                || routingKey == null) {
            return Optional.empty();
        }
        try {
            int parsedAttempt = Integer.parseInt(attempt.trim());
            long parsedRetriedAt = Long.parseLong(retriedAt.trim());
            if (parsedAttempt < 1) {
                return Optional.empty();
            }
            return Optional.of(new RetryHop(queue, parsedAttempt, parsedRetriedAt, exchange, routingKey));
        } catch (NumberFormatException malformed) {
            return Optional.empty();
        }
    }

    private static String text(Object header) {
        return header == null ? null : header.toString();
    }
}
