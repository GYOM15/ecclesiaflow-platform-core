package com.ecclesiaflow.platform.events.retry.amqp;

import com.ecclesiaflow.platform.events.retry.DelayedRetryTopology;
import com.ecclesiaflow.platform.events.retry.RetryHop;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A module's opted-in queues, and the one test that tells a redelivery from a retry stage apart from
 * any other delivery: the recoverer and the verifier must never disagree on it.
 */
public class DelayedRetryTopologies {

    private static final String DEFAULT_EXCHANGE = "";

    private final Map<String, DelayedRetryTopology> byQueue = new TreeMap<>();

    /** Throws {@link IllegalStateException} when two topologies claim the same queue. */
    public DelayedRetryTopologies(Collection<DelayedRetryTopology> topologies) {
        for (DelayedRetryTopology topology : topologies) {
            if (byQueue.putIfAbsent(topology.queue(), topology) != null) {
                throw new IllegalStateException("Queue '" + topology.queue() + "' has two delayed retry topologies");
            }
        }
    }

    public Optional<DelayedRetryTopology> forQueue(String queue) {
        return queue == null ? Optional.empty() : Optional.ofNullable(byQueue.get(queue));
    }

    public List<DelayedRetryTopology> all() {
        return List.copyOf(byQueue.values());
    }

    public boolean isEmpty() {
        return byQueue.isEmpty();
    }

    /**
     * The hop a message claims when it came back from a retry stage of the opted-in queue consuming it:
     * through the default exchange, under that queue's name, with every hop header naming that queue.
     * Nothing here proves the claim; {@link DelayedRetryVerifier} checks the stamp.
     */
    public Optional<RetryHop> claimedHop(Message message) {
        MessageProperties properties = message.getMessageProperties();
        String queue = properties.getConsumerQueue();
        if (forQueue(queue).isEmpty()
                || !DEFAULT_EXCHANGE.equals(properties.getReceivedExchange())
                || !queue.equals(properties.getReceivedRoutingKey())) {
            return Optional.empty();
        }
        return RetryHop.fromHeaders(properties.getHeaders()).filter(hop -> hop.queue().equals(queue));
    }
}
