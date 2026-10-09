package com.ecclesiaflow.platform.events.deadletter;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Queue;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Finds a module's dead-letter queues in the topology it declares, so a queue added later is
 * measured without anyone listing it. A dead-letter queue is one that receives what another queue
 * dead-letters: through that queue's {@code x-dead-letter-exchange} and
 * {@code x-dead-letter-routing-key}, or named by a {@link DeadLetterRoute} when a broker policy
 * carries those keys instead. Both are read, so a module can move its queues one at a time.
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
        return in(topology, List.of());
    }

    public static SortedSet<String> in(Collection<? extends Declarable> topology,
                                       Collection<DeadLetterRoute> routes) {
        List<Queue> queues = queues(topology);
        List<Binding> bindings = queueBindings(topology);
        Set<String> declared = queues.stream().map(Queue::getName).collect(Collectors.toSet());

        SortedSet<String> deadLetterQueues = new TreeSet<>();
        routes.forEach(route -> deadLetterQueues.add(route.deadLetterQueue()));
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

    /**
     * One policy per routed subscription, by queue name, sending its dead letters where the module binds
     * the dead-letter queue, or through the default exchange when it binds it nowhere. Throws
     * {@link IllegalStateException} for a route no single policy can serve.
     */
    public static List<DeadLetterPolicy> policies(Collection<? extends Declarable> topology,
                                                  Collection<DeadLetterRoute> routes) {
        Set<String> declared = queues(topology).stream().map(Queue::getName).collect(Collectors.toSet());
        List<Binding> bindings = queueBindings(topology);

        Map<String, String> deadLetterQueueByQueue = new TreeMap<>();
        for (DeadLetterRoute route : routes) {
            String previous = deadLetterQueueByQueue.putIfAbsent(route.queue(), route.deadLetterQueue());
            if (previous != null && !previous.equals(route.deadLetterQueue())) {
                throw new IllegalStateException("Queue '" + route.queue() + "' is routed to two dead-letter queues");
            }
        }
        return deadLetterQueueByQueue.entrySet().stream()
                .map(entry -> policy(entry.getKey(), entry.getValue(), declared, bindings))
                .toList();
    }

    private static DeadLetterPolicy policy(String queue, String deadLetterQueue, Set<String> declared,
                                           List<Binding> bindings) {
        if (!declared.contains(deadLetterQueue)) {
            throw new IllegalStateException("Dead-letter queue '" + deadLetterQueue + "' is not declared");
        }
        List<Binding> bound = bindings.stream()
                .filter(binding -> deadLetterQueue.equals(binding.getDestination()))
                .toList();
        if (bound.isEmpty()) {
            return new DeadLetterPolicy(queue, DEFAULT_EXCHANGE, deadLetterQueue);
        }
        if (bound.size() > 1) {
            throw new IllegalStateException("Dead-letter queue '" + deadLetterQueue
                    + "' is bound more than once; a policy names a single exchange and key");
        }
        return new DeadLetterPolicy(queue, bound.get(0).getExchange(), bound.get(0).getRoutingKey());
    }

    private static List<Queue> queues(Collection<? extends Declarable> topology) {
        return topology.stream().filter(Queue.class::isInstance).map(Queue.class::cast).toList();
    }

    private static List<Binding> queueBindings(Collection<? extends Declarable> topology) {
        return topology.stream()
                .filter(Binding.class::isInstance).map(Binding.class::cast)
                .filter(Binding::isDestinationQueue)
                .toList();
    }
}
