package com.ecclesiaflow.platform.events.retry.amqp;

import com.ecclesiaflow.grpc.events.church.MemberRemovedFromChurchEvent;
import com.ecclesiaflow.platform.events.retry.DelayedRetrySchedule;
import com.ecclesiaflow.platform.events.retry.DelayedRetryStamp;
import com.ecclesiaflow.platform.events.retry.DelayedRetryTopology;
import com.ecclesiaflow.platform.events.retry.RetryHop;
import com.ecclesiaflow.platform.events.retry.events.DelayedRetryEvents;
import com.ecclesiaflow.platform.events.retry.events.DelayedRetryEvents.DeadLetterCause;
import com.ecclesiaflow.platform.events.signing.DomainEventSigner;
import com.ecclesiaflow.platform.events.signing.DomainEventVerifier;
import com.ecclesiaflow.platform.events.signing.amqp.SigningMessagePostProcessor;
import com.ecclesiaflow.platform.events.signing.amqp.VerifyingListenerAdvice;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The stages on a real broker: a failed delivery waits out its stage, comes back to its queue with its
 * signature untouched, and is admitted by its stamp once the signature alone would be stale. A hop
 * forged without the secret, or a forged {@code x-death}, never reaches the listener.
 */
class DelayedRetryIntegrationTest {

    private static final String SECRET = "delayed-retry-it-secret";
    private static final String EXCHANGE = "it.retry.domain-events";
    private static final String QUEUE = "it.retry.subscriber.member-removed";
    private static final String DLQ = QUEUE + ".dlq";
    private static final String ROUTING_KEY = "it.retry.member.removed.v1";
    private static final String DEAD_KEY = "it.retry.member.removed-dead";
    // Shorter than the two stages together, so the last redelivery is older than a first delivery may be.
    private static final Duration FRESHNESS = Duration.ofSeconds(3);
    private static final DelayedRetrySchedule SCHEDULE = new DelayedRetrySchedule(
            List.of(Duration.ofSeconds(2), Duration.ofSeconds(3)), Duration.ofSeconds(10));
    private static final DelayedRetryTopology TOPOLOGY = new DelayedRetryTopology(QUEUE, SCHEDULE);

    @SuppressWarnings("resource")
    private static final GenericContainer<?> RABBITMQ =
            new GenericContainer<>(DockerImageName.parse("rabbitmq:3.13-alpine"))
                    .withExposedPorts(5672)
                    .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1));

    private static CachingConnectionFactory connections;
    private static RabbitAdmin admin;

    private final Clock clock = Clock.systemUTC();
    private final DomainEventSigner signer = new DomainEventSigner(SECRET);
    private final DomainEventVerifier verifier = new DomainEventVerifier(signer, true, FRESHNESS, clock);
    private final DelayedRetryStamp stamp = new DelayedRetryStamp(SECRET);
    private final DelayedRetryTopologies topologies = new DelayedRetryTopologies(List.of(TOPOLOGY));
    private final byte[] body = MemberRemovedFromChurchEvent.newBuilder()
            .setEventId("e-1").setChurchId("c-1").setMemberUserId("m-1").build().toByteArray();
    private final List<Delivery> deliveries = new CopyOnWriteArrayList<>();
    private final List<Object> events = new CopyOnWriteArrayList<>();
    private SimpleMessageListenerContainer listener;

    private record Delivery(Message message, long receivedAt) {
    }

    @BeforeAll
    static void startBroker() {
        RABBITMQ.start();
        connections = new CachingConnectionFactory(RABBITMQ.getHost(), RABBITMQ.getMappedPort(5672));
        connections.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connections.setPublisherReturns(true);
        admin = new RabbitAdmin(connections);

        TopicExchange exchange = new TopicExchange(EXCHANGE, true, false);
        Queue queue = QueueBuilder.durable(QUEUE)
                .withArgument("x-dead-letter-exchange", EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DEAD_KEY)
                .build();
        Queue deadLetters = QueueBuilder.durable(DLQ).build();
        admin.declareExchange(exchange);
        admin.declareQueue(queue);
        admin.declareQueue(deadLetters);
        admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with(ROUTING_KEY));
        admin.declareBinding(BindingBuilder.bind(deadLetters).to(exchange).with(DEAD_KEY));
        TOPOLOGY.retryQueues().forEach(admin::declareQueue);
    }

    @AfterAll
    static void stopBroker() {
        connections.destroy();
        RABBITMQ.stop();
    }

    @AfterEach
    void stopListener() {
        listener.stop();
        admin.purgeQueue(QUEUE, false);
        admin.purgeQueue(DLQ, false);
        TOPOLOGY.retryQueues().forEach(stage -> admin.purgeQueue(stage.getName(), false));
    }

    @Test
    @DisplayName("a failed delivery waits out each stage and is admitted by its stamp past the signature's window")
    void aFailedDeliveryComesBackThroughItsStages() {
        listen(2);

        publishSigned();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(deliveries).hasSize(3);
            assertThat(depthOf(QUEUE)).isZero();
        });
        Delivery first = deliveries.get(0);
        Delivery last = deliveries.get(2);
        assertThat(deliveries.get(1).receivedAt() - first.receivedAt())
                .isGreaterThanOrEqualTo(SCHEDULE.delay(1).toMillis() - 100);
        assertThat(last.receivedAt() - deliveries.get(1).receivedAt())
                .isGreaterThanOrEqualTo(SCHEDULE.delay(2).toMillis() - 100);

        MessageProperties redelivered = last.message().getMessageProperties();
        assertThat(redelivered.getReceivedExchange()).isEmpty();
        assertThat(redelivered.getReceivedRoutingKey()).isEqualTo(QUEUE);
        assertThat(RetryHop.fromHeaders(redelivered.getHeaders())).get()
                .returns(2, RetryHop::attempt)
                .returns(EXCHANGE, RetryHop::originalExchange)
                .returns(ROUTING_KEY, RetryHop::originalRoutingKey);
        assertThat(last.message().getBody()).isEqualTo(body);
        String signedAt = header(last, DomainEventSigner.SIGNED_AT_HEADER);
        assertThat(signedAt).isEqualTo(header(first, DomainEventSigner.SIGNED_AT_HEADER));
        assertThat(header(last, DomainEventSigner.SIGNATURE_HEADER))
                .isEqualTo(header(first, DomainEventSigner.SIGNATURE_HEADER));
        // The stamp, not the signature, admitted it: on the signature alone it was already stale.
        assertThat(last.receivedAt() - Long.parseLong(signedAt)).isGreaterThan(FRESHNESS.toMillis());
        assertThat(verifier.verify(EXCHANGE, ROUTING_KEY, body, header(last, DomainEventSigner.SIGNATURE_HEADER),
                signedAt, header(last, DomainEventSigner.SIGNATURE_VERSION_HEADER)))
                .isEqualTo(DomainEventVerifier.Decision.REJECT_STALE);

        assertThat(events).extracting(event -> ((DelayedRetryEvents.RetryScheduled) event).retryQueue())
                .containsExactly(TOPOLOGY.retryQueue(1).getName(), TOPOLOGY.retryQueue(2).getName());
        assertThat(depthOf(DLQ)).isZero();
    }

    @Test
    @DisplayName("the last stage's failure lands in the dead-letter queue")
    void theLastFailureIsDeadLettered() {
        listen(Integer.MAX_VALUE);

        publishSigned();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(depthOf(DLQ)).isEqualTo(1));
        assertThat(deliveries).hasSize(3);
        assertThat(events).hasSize(3);
        assertThat(events.get(2)).isInstanceOfSatisfying(DelayedRetryEvents.DeadLettered.class, dead -> {
            assertThat(dead.cause()).isEqualTo(DeadLetterCause.EXHAUSTED);
            assertThat(dead.retries()).isEqualTo(2);
        });
        assertThat(TOPOLOGY.retryQueues()).allSatisfy(stage -> assertThat(depthOf(stage.getName())).isZero());
    }

    @Test
    @DisplayName("a hop stamped without the secret, or a forged x-death, never reaches the listener")
    void aForgedRedeliveryIsRefused() {
        listen(0);
        long signedAt = clock.millis() - Duration.ofHours(1).toMillis();
        String signature = signer.sign(EXCHANGE, ROUTING_KEY, signedAt, body);
        RetryHop hop = new RetryHop(QUEUE, 1, clock.millis(), EXCHANGE, ROUTING_KEY);

        MessageProperties forgedStamp = signed(signedAt, signature);
        hop.headers().forEach(forgedStamp::setHeader);
        forgedStamp.setHeader(RetryHop.STAMP_HEADER, new DelayedRetryStamp("not-the-events-secret")
                .sign(hop, Long.toString(signedAt), signature, body));
        // On its own route, only its age can refuse it: RabbitMQ 3.13 keeps a publisher's x-death.
        MessageProperties forgedDeath = signed(signedAt, signature);
        forgedDeath.setHeader("x-death", List.of(Map.of("queue", TOPOLOGY.retryQueue(1).getName(),
                "reason", "expired", "count", 1L, "exchange", "", "routing-keys", List.of(QUEUE))));

        RabbitTemplate forger = new RabbitTemplate(connections);
        forger.send("", QUEUE, new Message(body, forgedStamp));
        forger.send(EXCHANGE, ROUTING_KEY, new Message(body, forgedDeath));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(depthOf(DLQ)).isEqualTo(2));
        assertThat(deliveries).isEmpty();
        assertThat(events).isEmpty();
    }

    private void listen(int failures) {
        AtomicInteger calls = new AtomicInteger();
        DelayedRetryMessageRecoverer recoverer = new DelayedRetryMessageRecoverer(topologies,
                DelayedRetryPublisher.on(connections, stamp, Duration.ofSeconds(5), clock), events::add);
        listener = new SimpleMessageListenerContainer(connections);
        listener.setQueueNames(QUEUE);
        listener.setDefaultRequeueRejected(false);
        // The modules' order: verification first, so a refused delivery never reaches the recoverer.
        listener.setAdviceChain(
                new VerifyingListenerAdvice(verifier, null,
                        new DelayedRetryVerifier(signer, stamp, topologies, true, FRESHNESS, clock)),
                RetryInterceptorBuilder.stateless().maxAttempts(1).recoverer(recoverer).build());
        listener.setMessageListener(message -> {
            deliveries.add(new Delivery(message, clock.millis()));
            if (calls.incrementAndGet() <= failures) {
                throw new IllegalStateException("Keycloak unreachable");
            }
        });
        listener.start();
    }

    private void publishSigned() {
        RabbitTemplate template = new RabbitTemplate(connections);
        template.addBeforePublishPostProcessors(new SigningMessagePostProcessor(signer, clock));
        template.send(EXCHANGE, ROUTING_KEY, new Message(body, new MessageProperties()));
    }

    private static MessageProperties signed(long signedAt, String signature) {
        MessageProperties properties = new MessageProperties();
        properties.setHeader(DomainEventSigner.SIGNED_AT_HEADER, Long.toString(signedAt));
        properties.setHeader(DomainEventSigner.SIGNATURE_HEADER, signature);
        properties.setHeader(DomainEventSigner.SIGNATURE_VERSION_HEADER, DomainEventSigner.SIGNATURE_VERSION);
        return properties;
    }

    private static String header(Delivery delivery, String name) {
        Object value = delivery.message().getMessageProperties().getHeader(name);
        return value == null ? null : value.toString();
    }

    private static long depthOf(String queue) {
        return admin.getQueueInfo(queue).getMessageCount();
    }
}
