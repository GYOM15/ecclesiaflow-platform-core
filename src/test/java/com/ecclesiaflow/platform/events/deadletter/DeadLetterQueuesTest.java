package com.ecclesiaflow.platform.events.deadletter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeadLetterQueuesTest {

    private static final TopicExchange EVENTS = new TopicExchange("ecclesiaflow.domain-events");

    @Test
    @DisplayName("Finds the queue bound under the exact dead-letter key of a subscription")
    void findsTheDeadLetterQueue() {
        assertThat(DeadLetterQueues.in(subscription("comm.subscriber.setup-token-issued",
                "auth.setup-token.issued.*", "auth.setup-token.issued-dead")))
                .containsExactly("comm.subscriber.setup-token-issued.dlq");
    }

    @Test
    @DisplayName("Finds the dead-letter queue of every subscription of a module")
    void findsEverySubscriptionsDeadLetterQueue() {
        List<Declarable> topology = new ArrayList<>(List.of(EVENTS));
        topology.addAll(subscription("members.subscriber.member-admitted-to-church",
                "church.member.admitted-to-church.*", "church.member.admitted-to-church-dead"));
        topology.addAll(subscription("members.subscriber.member-removed-from-church",
                "church.member.removed-from-church.*", "church.member.removed-from-church-dead"));

        assertThat(DeadLetterQueues.in(topology)).containsExactly(
                "members.subscriber.member-admitted-to-church.dlq",
                "members.subscriber.member-removed-from-church.dlq");
    }

    @Test
    @DisplayName("Ignores queues that dead-letter nowhere, and the subscriptions' own queues")
    void ignoresQueuesThatAreNotDeadLetterTargets() {
        Queue plain = QueueBuilder.durable("comm.email").build();
        Binding plainBinding = BindingBuilder.bind(plain).to(EVENTS).with("comm.email.*");

        assertThat(DeadLetterQueues.in(List.of(EVENTS, plain, plainBinding))).isEmpty();
    }

    @Test
    @DisplayName("Ignores a queue bound under another key or on another exchange")
    void ignoresBindingsThatDoNotReceiveTheDeadLetters() {
        Queue main = QueueBuilder.durable("q").deadLetterExchange(EVENTS.getName()).deadLetterRoutingKey("q-dead").build();
        Queue otherKey = QueueBuilder.durable("other-key").build();
        Queue otherExchange = QueueBuilder.durable("other-exchange").build();

        assertThat(DeadLetterQueues.in(List.of(main, otherKey, otherExchange,
                BindingBuilder.bind(otherKey).to(EVENTS).with("q-dead.v2"),
                BindingBuilder.bind(otherExchange).to(new DirectExchange("elsewhere")).with("q-dead"))))
                .isEmpty();
    }

    @Test
    @DisplayName("Never reports an exchange as a queue")
    void neverReportsAnExchange() {
        Queue main = QueueBuilder.durable("q").deadLetterExchange(EVENTS.getName()).deadLetterRoutingKey("q-dead").build();

        assertThat(DeadLetterQueues.in(List.of(main,
                BindingBuilder.bind(new DirectExchange("elsewhere")).to(EVENTS).with("q-dead"))))
                .isEmpty();
    }

    @Test
    @DisplayName("Does not follow a queue that dead-letters under its messages' own keys")
    void doesNotFollowImplicitKeys() {
        Queue main = QueueBuilder.durable("q").deadLetterExchange(EVENTS.getName()).build();
        Queue subscriber = QueueBuilder.durable("other.subscriber").build();

        assertThat(DeadLetterQueues.in(List.of(main, subscriber,
                BindingBuilder.bind(subscriber).to(EVENTS).with("church.member.#")))).isEmpty();
    }

    @Test
    @DisplayName("Through the default exchange, the dead-letter queue is the one named by the key")
    void followsTheDefaultExchange() {
        Queue main = QueueBuilder.durable("q").deadLetterExchange("").deadLetterRoutingKey("q.dlq").build();
        Queue dlq = QueueBuilder.durable("q.dlq").build();
        Queue undeclared = QueueBuilder.durable("r").deadLetterExchange("").deadLetterRoutingKey("r.dlq").build();

        assertThat(DeadLetterQueues.in(List.of(main, dlq, undeclared))).containsExactly("q.dlq");
    }

    @Test
    @DisplayName("Lists a dead-letter queue shared by several subscriptions once")
    void listsASharedDeadLetterQueueOnce() {
        Queue first = QueueBuilder.durable("a").deadLetterExchange(EVENTS.getName()).deadLetterRoutingKey("dead").build();
        Queue second = QueueBuilder.durable("b").deadLetterExchange(EVENTS.getName()).deadLetterRoutingKey("dead").build();
        Queue dlq = QueueBuilder.durable("shared.dlq").build();

        assertThat(DeadLetterQueues.in(List.of(first, second, dlq, BindingBuilder.bind(dlq).to(EVENTS).with("dead"))))
                .containsExactly("shared.dlq");
    }

    // The layout every module uses: the queue binds the event, its DLQ binds a distinct dead key.
    private static List<Declarable> subscription(String queue, String routingKey, String deadKey) {
        Queue main = QueueBuilder.durable(queue)
                .withArgument("x-dead-letter-exchange", EVENTS.getName())
                .withArgument("x-dead-letter-routing-key", deadKey)
                .build();
        Queue dlq = QueueBuilder.durable(queue + ".dlq").build();
        return List.of(main, dlq,
                BindingBuilder.bind(main).to(EVENTS).with(routingKey),
                BindingBuilder.bind(dlq).to(EVENTS).with(deadKey));
    }
}
