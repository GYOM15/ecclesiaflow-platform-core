package com.ecclesiaflow.platform.events.deadletter.autoconfigure;

import com.ecclesiaflow.platform.events.deadletter.DeadLetterQueueMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Boots through {@code @EnableAutoConfiguration}, as a consuming service does, so the registration order is real. */
class PlatformDeadLetterMetricsAutoConfigurationImportTest {

    private static final String DLQ = "comm.subscriber.setup-token-issued.dlq";

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class ModuleWithASubscription {

        private static final TopicExchange EVENTS = new TopicExchange("ecclesiaflow.domain-events");

        @Bean
        AmqpAdmin amqpAdmin() {
            AmqpAdmin admin = mock(AmqpAdmin.class);
            when(admin.getQueueInfo(DLQ)).thenReturn(new QueueInformation(DLQ, 2, 0));
            return admin;
        }

        @Bean
        Queue setupTokenIssuedQueue() {
            return QueueBuilder.durable("comm.subscriber.setup-token-issued")
                    .deadLetterExchange(EVENTS.getName())
                    .deadLetterRoutingKey("auth.setup-token.issued-dead")
                    .build();
        }

        @Bean
        Queue setupTokenIssuedDlqQueue() {
            return QueueBuilder.durable(DLQ).build();
        }

        @Bean
        Binding setupTokenIssuedDlqBinding(Queue setupTokenIssuedDlqQueue) {
            return BindingBuilder.bind(setupTokenIssuedDlqQueue).to(EVENTS).with("auth.setup-token.issued-dead");
        }
    }

    @Test
    void measuresTheModulesDeadLetterQueue() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ModuleWithASubscription.class)
                .web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off")
                .run()) {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            context.getBean(DeadLetterQueueMetrics.class).bindTo(registry);

            assertThat(registry.get(DeadLetterQueueMetrics.DLQ_DEPTH).tag("queue", DLQ).gauge().value())
                    .isEqualTo(2d);
        }
    }
}
