package com.ecclesiaflow.platform.events.deadletter.autoconfigure;

import com.ecclesiaflow.platform.events.deadletter.DeadLetterQueueMetrics;
import com.ecclesiaflow.platform.events.deadletter.DeadLetterRoute;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PlatformDeadLetterMetricsAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformDeadLetterMetricsAutoConfiguration.class))
            .withBean(AmqpAdmin.class, () -> mock(AmqpAdmin.class))
            .withUserConfiguration(Subscription.class, GroupedSubscription.class);

    @Configuration(proxyBeanMethods = false)
    static class Subscription {

        private static final TopicExchange EVENTS = new TopicExchange("ecclesiaflow.domain-events");

        @Bean
        TopicExchange domainEventsExchange() {
            return EVENTS;
        }

        @Bean
        Queue removedQueue() {
            return QueueBuilder.durable("chat.subscriber.member-removed-from-church")
                    .deadLetterExchange(EVENTS.getName())
                    .deadLetterRoutingKey("church.member.removed-from-church-dead")
                    .build();
        }

        @Bean
        Queue removedDlqQueue() {
            return QueueBuilder.durable("chat.subscriber.member-removed-from-church.dlq").build();
        }

        @Bean
        Binding removedBinding(Queue removedQueue) {
            return BindingBuilder.bind(removedQueue).to(EVENTS).with("church.member.removed-from-church.*");
        }

        @Bean
        Binding removedDlqBinding(Queue removedDlqQueue) {
            return BindingBuilder.bind(removedDlqQueue).to(EVENTS).with("church.member.removed-from-church-dead");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class GroupedSubscription {

        @Bean
        Declarables admittedTopology() {
            Queue main = QueueBuilder.durable("chat.subscriber.member-admitted-to-church")
                    .deadLetterExchange("ecclesiaflow.domain-events")
                    .deadLetterRoutingKey("church.member.admitted-to-church-dead")
                    .build();
            Queue dlq = QueueBuilder.durable("chat.subscriber.member-admitted-to-church.dlq").build();
            return new Declarables(main, dlq, new Binding(dlq.getName(), Binding.DestinationType.QUEUE,
                    "ecclesiaflow.domain-events", "church.member.admitted-to-church-dead", null));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RoutedSubscription {

        @Bean
        Queue addedQueue() {
            return QueueBuilder.durable("chat.subscriber.member-added-to-group").build();
        }

        @Bean
        Queue addedDlqQueue() {
            return QueueBuilder.durable("chat.subscriber.member-added-to-group.dlq").build();
        }

        @Bean
        DeadLetterRoute addedDeadLetterRoute() {
            return new DeadLetterRoute("chat.subscriber.member-added-to-group",
                    "chat.subscriber.member-added-to-group.dlq");
        }
    }

    @Test
    @DisplayName("Measures the dead-letter queue a route names, beside those found by their arguments")
    void measuresRoutedDeadLetterQueues() {
        runner.withUserConfiguration(RoutedSubscription.class)
                .run(context -> assertThat(context.getBean(DeadLetterQueueMetrics.class).queues())
                        .containsExactly(
                                "chat.subscriber.member-added-to-group.dlq",
                                "chat.subscriber.member-admitted-to-church.dlq",
                                "chat.subscriber.member-removed-from-church.dlq"));
    }

    @Test
    @DisplayName("Measures the dead-letter queue of every subscription the module declares")
    void measuresEveryDeclaredDeadLetterQueue() {
        runner.run(context -> assertThat(context.getBean(DeadLetterQueueMetrics.class).queues())
                .containsExactly(
                        "chat.subscriber.member-admitted-to-church.dlq",
                        "chat.subscriber.member-removed-from-church.dlq"));
    }

    @Test
    @DisplayName("Can be switched off")
    void canBeSwitchedOff() {
        runner.withPropertyValues("ecclesiaflow.events.dead-letter-metrics.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(DeadLetterQueueMetrics.class));
    }

    @Test
    @DisplayName("Stays out of a module without RabbitMQ or without Micrometer")
    void absentWithoutItsLibraries() {
        runner.withClassLoader(new FilteredClassLoader(AmqpAdmin.class))
                .run(context -> assertThat(context).doesNotHaveBean(DeadLetterQueueMetrics.class));
        runner.withClassLoader(new FilteredClassLoader(MeterBinder.class))
                .run(context -> assertThat(context).doesNotHaveBean(DeadLetterQueueMetrics.class));
    }

    @Test
    @DisplayName("Backs off when the module declares its own")
    void backsOffForTheModulesOwn() {
        DeadLetterQueueMetrics own = new DeadLetterQueueMetrics(null, List.of());

        runner.withBean(DeadLetterQueueMetrics.class, () -> own)
                .run(context -> assertThat(context.getBean(DeadLetterQueueMetrics.class)).isSameAs(own));
    }
}
