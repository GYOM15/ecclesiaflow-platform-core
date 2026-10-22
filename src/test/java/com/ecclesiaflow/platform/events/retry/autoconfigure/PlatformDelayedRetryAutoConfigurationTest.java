package com.ecclesiaflow.platform.events.retry.autoconfigure;

import com.ecclesiaflow.platform.events.deadletter.DeadLetterRoute;
import com.ecclesiaflow.platform.events.retry.DelayedRetryStamp;
import com.ecclesiaflow.platform.events.retry.DelayedRetryTopology;
import com.ecclesiaflow.platform.events.retry.RetryHop;
import com.ecclesiaflow.platform.events.retry.amqp.DelayedRetryMessageRecoverer;
import com.ecclesiaflow.platform.events.retry.amqp.DelayedRetryQueueMetrics;
import com.ecclesiaflow.platform.events.retry.amqp.DelayedRetryTopologies;
import com.ecclesiaflow.platform.events.retry.events.DelayedRetryEventListener;
import com.ecclesiaflow.platform.events.retry.events.DelayedRetryMetrics;
import com.ecclesiaflow.platform.events.signing.DomainEventSigner;
import com.ecclesiaflow.platform.events.signing.amqp.VerifyingListenerAdvice;
import com.ecclesiaflow.platform.events.signing.autoconfigure.PlatformEventSigningAmqpAutoConfiguration;
import com.ecclesiaflow.platform.events.signing.autoconfigure.PlatformEventSigningAutoConfiguration;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatformDelayedRetryAutoConfigurationTest {

    private static final String SECRET = "auto-configuration-secret";
    private static final String QUEUE = "auth.subscriber.member-account-status";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformEventSigningAutoConfiguration.class,
                    PlatformEventSigningAmqpAutoConfiguration.class, PlatformDelayedRetryAutoConfiguration.class))
            .withBean(ConnectionFactory.class, () -> reliableFactory(true));

    private static ConnectionFactory reliableFactory(boolean confirms) {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        when(factory.isPublisherConfirms()).thenReturn(confirms);
        when(factory.isPublisherReturns()).thenReturn(true);
        return factory;
    }

    @Configuration(proxyBeanMethods = false)
    static class OptedIn {

        @Bean
        Queue memberAccountStatusQueue() {
            return QueueBuilder.durable(QUEUE).build();
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

    @Configuration(proxyBeanMethods = false)
    static class DeadLetteringByArguments {

        @Bean
        Queue memberAccountStatusQueue() {
            return QueueBuilder.durable(QUEUE).deadLetterExchange("ecclesiaflow.domain-events")
                    .deadLetterRoutingKey("auth.members.account.status-dead").build();
        }

        @Bean
        DelayedRetryTopology memberAccountStatusRetries() {
            return new DelayedRetryTopology(QUEUE);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class WithoutDeadLetterRoute {

        @Bean
        Queue memberAccountStatusQueue() {
            return QueueBuilder.durable(QUEUE).build();
        }

        @Bean
        DelayedRetryTopology memberAccountStatusRetries() {
            return new DelayedRetryTopology(QUEUE);
        }
    }

    @Test
    @DisplayName("Leaves a module without a topology untouched, with the signing advice it had")
    void inactiveWithoutTopology() {
        runner.withPropertyValues("ecclesiaflow.events.hmac-secret=" + SECRET).run(context -> {
            assertThat(context).doesNotHaveBean(DelayedRetryMessageRecoverer.class)
                    .doesNotHaveBean(DelayedRetryTopologies.class)
                    .hasSingleBean(VerifyingListenerAdvice.class)
                    .hasBean("verifyingListenerAdvice");
        });
    }

    @Nested
    @DisplayName("Once a queue opts in")
    class OptedInModule {

        @Test
        @DisplayName("provides the recoverer, the listener, the counters and one gauge per stage")
        void providesTheRecoverer() {
            runner.withUserConfiguration(OptedIn.class).run(context -> {
                assertThat(context).hasSingleBean(DelayedRetryMessageRecoverer.class)
                        .hasSingleBean(DelayedRetryEventListener.class)
                        .hasSingleBean(DelayedRetryMetrics.class);
                assertThat(context.getBean(DelayedRetryQueueMetrics.class).queues()).containsExactly(
                        QUEUE + ".retry.1m", QUEUE + ".retry.5m", QUEUE + ".retry.30m", QUEUE + ".retry.2h");
                assertThat(context.getBean(DelayedRetryStamp.class).isEnabled()).isFalse();
            });
        }

        @Test
        @DisplayName("starts with a blank secret, which signs and stamps nothing")
        void startsWithBlankSecret() {
            runner.withUserConfiguration(OptedIn.class)
                    .withPropertyValues("ecclesiaflow.events.hmac-secret=")
                    .run(context -> {
                        assertThat(context).hasSingleBean(DelayedRetryMessageRecoverer.class)
                                .hasBean("retryAwareVerifyingListenerAdvice");
                        assertThat(context.getBean(DelayedRetryStamp.class).isEnabled()).isFalse();
                    });
        }

        @Test
        @DisplayName("accepts a queue that dead-letters by its own arguments")
        void acceptsDeadLetterArguments() {
            runner.withUserConfiguration(DeadLetteringByArguments.class)
                    .run(context -> assertThat(context).hasSingleBean(DelayedRetryMessageRecoverer.class));
        }

        @Test
        @DisplayName("replaces the signing advice with one that verifies redeliveries from the stages")
        void verifiesRedeliveries() {
            runner.withUserConfiguration(OptedIn.class)
                    .withPropertyValues("ecclesiaflow.events.hmac-secret=" + SECRET,
                            "ecclesiaflow.events.verify-signatures=true")
                    .run(context -> {
                        assertThat(context).hasSingleBean(VerifyingListenerAdvice.class)
                                .hasBean("retryAwareVerifyingListenerAdvice");
                        MethodInvocation invocation = mock(MethodInvocation.class);
                        when(invocation.getArguments()).thenReturn(new Object[]{redeliveredTwoHoursLater()});
                        when(invocation.proceed()).thenReturn("listener-result");

                        assertThat(context.getBean(VerifyingListenerAdvice.class).invoke(invocation))
                                .isEqualTo("listener-result");
                    });
        }
    }

    @Nested
    @DisplayName("Refuses to start half-wired")
    class HalfWired {

        @Test
        @DisplayName("when an opted-in queue dead-letters nowhere")
        void withoutDeadLetterRoute() {
            runner.withUserConfiguration(WithoutDeadLetterRoute.class).run(context ->
                    assertThat(context).hasFailed().getFailure().rootCause()
                            .hasMessageContaining("Queue '" + QUEUE + "' has delayed retries but no dead-letter route"));
        }

        @Test
        @DisplayName("when an opted-in queue names a dead-letter exchange without a key")
        void withDeadLetterExchangeOnly() {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(PlatformDelayedRetryAutoConfiguration.class))
                    .withBean(ConnectionFactory.class, () -> reliableFactory(true))
                    .withBean("memberAccountStatusQueue", Queue.class, () -> QueueBuilder.durable(QUEUE)
                            .deadLetterExchange("ecclesiaflow.domain-events").build())
                    .withBean(DelayedRetryTopology.class, () -> new DelayedRetryTopology(QUEUE))
                    .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                            .hasMessageContaining("no dead-letter route"));
        }

        @Test
        @DisplayName("when the connection factory cannot confirm")
        void withoutConfirms() {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(PlatformDelayedRetryAutoConfiguration.class))
                    .withBean(ConnectionFactory.class, () -> reliableFactory(false))
                    .withUserConfiguration(OptedIn.class)
                    .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                            .hasMessageContaining("publisher-confirm-type=correlated"));
        }

        @Test
        @DisplayName("when events are signed but no secret is there to stamp the retries")
        void signingWithoutStamp() {
            runner.withUserConfiguration(OptedIn.class)
                    .withBean(DomainEventSigner.class, () -> new DomainEventSigner(SECRET))
                    .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                            .hasMessageContaining("cannot be stamped"));
        }
    }

    /** A copy of a 2 h 40 min old event, back from the 2 h stage, as the recoverer wrote it. */
    private static Message redeliveredTwoHoursLater() {
        DomainEventSigner signer = new DomainEventSigner(SECRET);
        byte[] body = "deactivated".getBytes(StandardCharsets.UTF_8);
        long signedAt = Instant.now().minus(Duration.ofMinutes(160)).toEpochMilli();
        String signature = signer.sign("ecclesiaflow.domain-events", "members.account.deactivated", signedAt, body);
        RetryHop hop = new RetryHop(QUEUE, 4, Instant.now().minus(Duration.ofMinutes(121)).toEpochMilli(),
                "ecclesiaflow.domain-events", "members.account.deactivated");
        MessageProperties properties = new MessageProperties();
        properties.setConsumerQueue(QUEUE);
        properties.setReceivedExchange("");
        properties.setReceivedRoutingKey(QUEUE);
        properties.setHeader(DomainEventSigner.SIGNATURE_HEADER, signature);
        properties.setHeader(DomainEventSigner.SIGNED_AT_HEADER, Long.toString(signedAt));
        hop.headers().forEach(properties::setHeader);
        properties.setHeader(RetryHop.STAMP_HEADER,
                new DelayedRetryStamp(SECRET).sign(hop, Long.toString(signedAt), signature, body));
        return new Message(body, properties);
    }
}
