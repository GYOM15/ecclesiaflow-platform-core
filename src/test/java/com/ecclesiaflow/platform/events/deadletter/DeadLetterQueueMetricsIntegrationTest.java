package com.ecclesiaflow.platform.events.deadletter;

import com.ecclesiaflow.grpc.events.church.MemberRemovedFromChurchEvent;
import com.ecclesiaflow.platform.events.amqp.ProtobufMessageConverter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** The converter and the gauge on a real broker: what the converter refuses parks, and the depth shows it. */
class DeadLetterQueueMetricsIntegrationTest {

    private static final String EXCHANGE = "it.domain-events";
    private static final String QUEUE = "it.subscriber.member-removed-from-church";
    private static final String DLQ = "it.subscriber.member-removed-from-church.dlq";
    private static final String ROUTING_KEY = "it.member.removed-from-church.v1";
    private static final String DEAD_KEY = "it.member.removed-from-church-dead";

    @SuppressWarnings("resource")
    private static final GenericContainer<?> RABBITMQ =
            new GenericContainer<>(DockerImageName.parse("rabbitmq:3.13-alpine"))
                    .withExposedPorts(5672)
                    .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1));

    private static CachingConnectionFactory connections;
    private static RabbitAdmin admin;
    private static List<Declarable> topology;

    private final ProtobufMessageConverter converter = new ProtobufMessageConverter();
    private final List<Object> received = new CopyOnWriteArrayList<>();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private SimpleMessageListenerContainer listener;

    @BeforeAll
    static void startBroker() {
        RABBITMQ.start();
        connections = new CachingConnectionFactory(RABBITMQ.getHost(), RABBITMQ.getMappedPort(5672));
        admin = new RabbitAdmin(connections);

        TopicExchange exchange = new TopicExchange(EXCHANGE, true, false);
        Queue queue = QueueBuilder.durable(QUEUE)
                .withArgument("x-dead-letter-exchange", EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DEAD_KEY)
                .build();
        Queue deadLetters = QueueBuilder.durable(DLQ).build();
        topology = List.of(exchange, queue, deadLetters,
                BindingBuilder.bind(queue).to(exchange).with("it.member.removed-from-church.*"),
                BindingBuilder.bind(deadLetters).to(exchange).with(DEAD_KEY));
        for (Declarable declarable : topology) {
            if (declarable instanceof Exchange declared) {
                admin.declareExchange(declared);
            } else if (declarable instanceof Queue declared) {
                admin.declareQueue(declared);
            } else {
                admin.declareBinding((Binding) declarable);
            }
        }
    }

    @AfterAll
    static void stopBroker() {
        connections.destroy();
        RABBITMQ.stop();
    }

    @BeforeEach
    void startListener() {
        listener = new SimpleMessageListenerContainer(connections);
        listener.setQueueNames(QUEUE);
        listener.setDefaultRequeueRejected(false);
        listener.setMessageListener(message -> received.add(converter.fromMessage(message)));
        listener.start();
    }

    @AfterEach
    void stopListener() {
        listener.stop();
        admin.purgeQueue(QUEUE, false);
        admin.purgeQueue(DLQ, false);
    }

    @Test
    @DisplayName("a message the strict converter refuses parks in the dead-letter queue the gauge found")
    void aRefusedMessageParksAndIsCounted() {
        DeadLetterQueueMetrics metrics = new DeadLetterQueueMetrics(admin, DeadLetterQueues.in(topology));
        metrics.bindTo(registry);
        RabbitTemplate template = new RabbitTemplate(connections);
        template.setMessageConverter(converter);
        template.setExchange(EXCHANGE);
        MemberRemovedFromChurchEvent removal = MemberRemovedFromChurchEvent.newBuilder()
                .setEventId("e-1").setChurchId("c-1").setMemberUserId("m-1").build();

        assertThat(metrics.queues()).containsExactly(DLQ);
        assertThat(depthOf(DLQ)).isZero();

        template.convertAndSend(ROUTING_KEY, removal);
        template.send(ROUTING_KEY, new Message(removal.toByteArray(), new MessageProperties()));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(received).containsExactly(removal);
            assertThat(depthOf(DLQ)).isEqualTo(1d);
        });
    }

    @Test
    @DisplayName("a queue the broker does not know reads unknown, not empty")
    void anUndeclaredQueueReadsUnknown() {
        new DeadLetterQueueMetrics(admin, List.of("it.never-declared.dlq")).bindTo(registry);

        assertThat(depthOf("it.never-declared.dlq")).isEqualTo(DeadLetterQueueMetrics.UNKNOWN_DEPTH);
    }

    private double depthOf(String queue) {
        return registry.get(DeadLetterQueueMetrics.DLQ_DEPTH).tag("queue", queue).gauge().value();
    }
}
