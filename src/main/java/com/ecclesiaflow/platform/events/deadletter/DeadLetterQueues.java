package com.ecclesiaflow.platform.events.deadletter;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Queue;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Finds a module's dead-letter queues in the topology it declares, so a queue added later is
 * measured without anyone listing it: a dead-letter queue is one that receives what another queue
 * dead-letters, through its {@code x-dead-letter-exchange} and {@code x-dead-letter-routing-key}.
 *
 * <p>Not followed: a queue that dead-letters under its messages' own routing keys (no explicit key),
 * since on the shared topic exchange those keys reach the live subscriptions too, and
 * exchange-to-exchange bindings. No module declares either.</p>
 */
public final class DeadLetterQueues {

    static final String DEAD_LETTER_EXCHANGE = "x-dead-letter-exchange";
    static final String DEAD_LETTER_ROUTING_KEY = "x-dead-letter-routing-key";
    private static final String DEFAULT_EXCHANGE = "";

    private DeadLetterQueues() {
    }

    public static SortedSet<String> in(Collection<? extends Declarable> topology) {
        List<Queue> queues = topology.stream().filter(Queue.class::isInstance).map(Queue.class::cast).toList();
        List<Binding> bindings = topology.stream()
                .filter(Binding.class::isInstance).map(Binding.class::cast)
                .filter(Binding::isDestinationQueue)
                .toList();
        Set<String> declared = queues.stream().map(Queue::getName).collect(Collectors.toSet());

        SortedSet<String> deadLetterQueues = new TreeSet<>();
        for (Queue queue : queues) {
            Object exchange = queue.getArguments().get(DEAD_LETTER_EXCHANGE);
            Object routingKey = queue.getArguments().get(DEAD_LETTER_ROUTING_KEY);
            if (exchange == null || routingKey == null) {
                continue;
            }
            // The default exchange routes a key to the queue of that name, with no binding to declare.
            if (DEFAULT_EXCHANGE.equals(exchange)) {
                if (declared.contains(routingKey)) {
                    deadLetterQueues.add((String) routingKey);
                }
                continue;
            }
            for (Binding binding : bindings) {
                if (exchange.equals(binding.getExchange()) && routingKey.equals(binding.getRoutingKey())) {
                    deadLetterQueues.add(binding.getDestination());
                }
            }
        }
        return deadLetterQueues;
    }
}
