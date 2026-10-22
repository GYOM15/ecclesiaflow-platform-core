package com.ecclesiaflow.platform.events.retry.autoconfigure;

import com.ecclesiaflow.platform.events.deadletter.DeadLetterQueueMetrics;
import com.ecclesiaflow.platform.events.deadletter.DeadLetterRoute;
import com.ecclesiaflow.platform.events.retry.DelayedRetryTopology;
import com.ecclesiaflow.platform.events.retry.amqp.DelayedRetryMessageRecoverer;
import com.ecclesiaflow.platform.events.signing.amqp.VerifyingListenerAdvice;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Boots through {@code @EnableAutoConfiguration}, as a consuming service does, so the registration order is real. */
class PlatformDelayedRetryAutoConfigurationImportTest {

    private static final String QUEUE = "auth.subscriber.member-account-status";

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class ModuleWithRetries {

        @Bean
        AmqpAdmin amqpAdmin() {
            return mock(AmqpAdmin.class);
        }

        @Bean
        Queue memberAccountStatusQueue() {
            return QueueBuilder.durable(QUEUE).build();
        }

        @Bean
        Queue memberAccountStatusDlqQueue() {
            return QueueBuilder.durable(QUEUE + ".dlq").build();
        }

        @Bean
        DeadLetterRoute memberAccountStatusDeadLetterRoute() {
            return new DeadLetterRoute(QUEUE, QUEUE + ".dlq");
        }

        @Bean
        DelayedRetryTopology memberAccountStatusRetries() {
            return new DelayedRetryTopology(QUEUE);
        }
    }

    @Test
    void wiresOneRetryAwareAdviceAndMeasuresOnlyTheRealDeadLetterQueue() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ModuleWithRetries.class)
                .web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off",
                        "spring.rabbitmq.publisher-confirm-type=correlated",
                        "spring.rabbitmq.publisher-returns=true",
                        "ecclesiaflow.events.hmac-secret=import-test-secret")
                .run()) {
            assertThat(context.getBeansOfType(VerifyingListenerAdvice.class))
                    .containsOnlyKeys("retryAwareVerifyingListenerAdvice");
            assertThat(context.getBeansOfType(DelayedRetryMessageRecoverer.class)).hasSize(1);
            assertThat(context.getBean(DeadLetterQueueMetrics.class).queues()).containsExactly(QUEUE + ".dlq");
        }
    }
}
