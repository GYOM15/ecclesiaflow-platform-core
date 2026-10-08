package com.ecclesiaflow.platform.events.outbox.autoconfigure;

import com.ecclesiaflow.platform.events.outbox.OutboxMessage;
import com.ecclesiaflow.platform.events.outbox.OutboxProperties;
import com.ecclesiaflow.platform.events.outbox.OutboxPublisher;
import com.ecclesiaflow.platform.events.outbox.amqp.AmqpOutboxMessageMapper;
import com.ecclesiaflow.platform.events.outbox.events.OutboxRelayEventListener;
import com.ecclesiaflow.platform.events.outbox.events.OutboxRelayMetrics;
import com.ecclesiaflow.platform.events.outbox.jdbc.JdbcOutboxPublisher;
import com.ecclesiaflow.platform.events.outbox.relay.OutboxRelay;
import com.ecclesiaflow.platform.events.outbox.relay.OutboxRelayScheduler;
import com.ecclesiaflow.platform.events.outbox.relay.OutboxRepository;
import com.ecclesiaflow.platform.events.signing.DomainEventSigner;
import com.ecclesiaflow.platform.events.signing.amqp.SigningMessagePostProcessor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatformOutboxAutoConfigurationTest {

    static RabbitTemplate reliableTemplate() {
        ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
        when(connectionFactory.isPublisherConfirms()).thenReturn(true);
        when(connectionFactory.isPublisherReturns()).thenReturn(true);
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMandatory(true);
        return template;
    }

    private final ApplicationContextRunner bare = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformOutboxAutoConfiguration.class));

    /** A module with a database and a domain-events template, as auth, church and members have. */
    private final ApplicationContextRunner module = bare
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean("domainEventsRabbitTemplate", RabbitTemplate.class, PlatformOutboxAutoConfigurationTest::reliableTemplate)
            // Keep the scheduler from polling the mocked DataSource while the context is up.
            .withPropertyValues("ecclesiaflow.events.outbox.poll-interval=1h");

    @Nested
    @DisplayName("not enabled")
    class NotEnabled {

        @Test
        @DisplayName("Contributes nothing to a module that does not enable it")
        void inertByDefault() {
            module.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(OutboxPublisher.class);
                assertThat(context).doesNotHaveBean(OutboxRepository.class);
                assertThat(context).doesNotHaveBean(OutboxRelay.class);
                assertThat(context).doesNotHaveBean(OutboxRelayScheduler.class);
                assertThat(context).doesNotHaveBean(OutboxRelayEventListener.class);
                assertThat(context).doesNotHaveBean(OutboxProperties.class);
            });
        }

        @Test
        @DisplayName("Contributes nothing when explicitly disabled")
        void inertWhenDisabled() {
            module.withPropertyValues("ecclesiaflow.events.outbox.enabled=false")
                    .run(context -> assertThat(context).doesNotHaveBean(OutboxPublisher.class));
        }

        @Test
        @DisplayName("Asks nothing of a module without a database or a broker")
        void inertWithoutInfrastructure() {
            bare.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(OutboxPublisher.class));
        }
    }

    @Nested
    @DisplayName("enabled")
    class Enabled {

        @Test
        @DisplayName("Wires the writer, the relay, its schedule and its listeners")
        void wiresEverything() {
            module.withPropertyValues("ecclesiaflow.events.outbox.enabled=true").run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(OutboxPublisher.class);
                assertThat(context.getBean(OutboxPublisher.class)).isInstanceOf(JdbcOutboxPublisher.class);
                assertThat(context).hasSingleBean(AmqpOutboxMessageMapper.class);
                assertThat(context).hasSingleBean(OutboxRepository.class);
                assertThat(context).hasSingleBean(OutboxRelay.class);
                assertThat(context).hasSingleBean(OutboxRelayScheduler.class);
                assertThat(context).hasSingleBean(OutboxRelayEventListener.class);
                assertThat(context).hasSingleBean(OutboxRelayMetrics.class);
                assertThat(context.getBean(OutboxRelayScheduler.class).isRunning()).isTrue();
            });
        }

        @Test
        @DisplayName("Binds every setting")
        void bindsSettings() {
            module.withPropertyValues(
                    "ecclesiaflow.events.outbox.enabled=true",
                    "ecclesiaflow.events.outbox.rabbit-template=domainEventsRabbitTemplate",
                    "ecclesiaflow.events.outbox.batch-size=20",
                    "ecclesiaflow.events.outbox.confirm-timeout=3s",
                    "ecclesiaflow.events.outbox.lease=90s",
                    "ecclesiaflow.events.outbox.max-attempts=4",
                    "ecclesiaflow.events.outbox.initial-backoff=2s",
                    "ecclesiaflow.events.outbox.backoff-multiplier=3",
                    "ecclesiaflow.events.outbox.max-backoff=1m",
                    "ecclesiaflow.events.outbox.sent-retention=3d",
                    "ecclesiaflow.events.outbox.purge-interval=30m").run(context -> {
                assertThat(context).hasNotFailed();
                OutboxProperties properties = context.getBean(OutboxProperties.class);
                assertThat(properties.isEnabled()).isTrue();
                assertThat(properties.getRabbitTemplate()).isEqualTo("domainEventsRabbitTemplate");
                assertThat(properties.getPollInterval()).isEqualTo(Duration.ofHours(1));
                assertThat(properties.getBatchSize()).isEqualTo(20);
                assertThat(properties.getConfirmTimeout()).isEqualTo(Duration.ofSeconds(3));
                assertThat(properties.getLease()).isEqualTo(Duration.ofSeconds(90));
                assertThat(properties.getMaxAttempts()).isEqualTo(4);
                assertThat(properties.getInitialBackoff()).isEqualTo(Duration.ofSeconds(2));
                assertThat(properties.getBackoffMultiplier()).isEqualTo(3.0);
                assertThat(properties.getMaxBackoff()).isEqualTo(Duration.ofMinutes(1));
                assertThat(properties.getSentRetention()).isEqualTo(Duration.ofDays(3));
                assertThat(properties.getPurgeInterval()).isEqualTo(Duration.ofMinutes(30));
            });
        }

        @Test
        @DisplayName("Ships defaults that relay within a second, park after about half an hour of failures "
                + "and keep relayed rows for an hour")
        void defaults() {
            OutboxProperties defaults = new OutboxProperties();

            assertThat(defaults.isEnabled()).isFalse();
            assertThat(defaults.getRabbitTemplate()).isEqualTo("domainEventsRabbitTemplate");
            assertThat(defaults.getPollInterval()).isEqualTo(Duration.ofSeconds(1));
            assertThat(defaults.getBatchSize()).isEqualTo(50);
            assertThat(defaults.getConfirmTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(defaults.getLease()).isEqualTo(Duration.ofMinutes(2));
            assertThat(defaults.getMaxAttempts()).isEqualTo(10);
            assertThat(defaults.getInitialBackoff()).isEqualTo(Duration.ofSeconds(5));
            assertThat(defaults.getBackoffMultiplier()).isEqualTo(2.0);
            assertThat(defaults.getMaxBackoff()).isEqualTo(Duration.ofMinutes(10));
            assertThat(defaults.getSentRetention()).isEqualTo(Duration.ofHours(1));
            assertThat(defaults.getPurgeInterval()).isEqualTo(Duration.ofMinutes(5));
        }

        @Test
        @DisplayName("Uses the template the module names")
        void usesNamedTemplate() {
            module.withBean("billingEventsTemplate", RabbitTemplate.class, PlatformOutboxAutoConfigurationTest::reliableTemplate)
                    .withPropertyValues("ecclesiaflow.events.outbox.enabled=true",
                            "ecclesiaflow.events.outbox.rabbit-template=billingEventsTemplate")
                    .run(context -> assertThat(context).hasNotFailed().hasSingleBean(OutboxRelay.class));
        }

        @Test
        @DisplayName("Keeps a module's own publisher")
        void backsOffForModuleBean() {
            OutboxPublisher own = new OutboxPublisher() {
                @Override
                public void append(String exchange, String routingKey, Object event, String aggregateKey) {
                }

                @Override
                public void append(OutboxMessage message, String aggregateKey) {
                }
            };
            module.withBean(OutboxPublisher.class, () -> own)
                    .withPropertyValues("ecclesiaflow.events.outbox.enabled=true")
                    .run(context -> assertThat(context.getBean(OutboxPublisher.class)).isSameAs(own));
        }

        @Test
        @DisplayName("Runs without Micrometer on the classpath")
        void runsWithoutMicrometer() {
            module.withClassLoader(new FilteredClassLoader(MeterRegistry.class))
                    .withPropertyValues("ecclesiaflow.events.outbox.enabled=true")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).hasSingleBean(OutboxRelay.class);
                        assertThat(context).doesNotHaveBean(OutboxRelayMetrics.class);
                    });
        }

        @Test
        @DisplayName("Registers its gauges on the module's registry")
        void registersGauges() {
            module.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                    .withPropertyValues("ecclesiaflow.events.outbox.enabled=true")
                    .run(context -> assertThat(context.getBean(MeterRegistry.class)
                            .find(OutboxRelayMetrics.PARKED_METRIC).gauge()).isNotNull());
        }

        @Test
        @DisplayName("Keeps its JdbcTemplate private, so Boot still gives the module its own")
        void registersNoJdbcTemplate() {
            module.withPropertyValues("ecclesiaflow.events.outbox.enabled=true")
                    .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(JdbcTemplate.class));
        }
    }

    @Nested
    @DisplayName("refuses to start")
    class RefusesToStart {

        @Test
        @DisplayName("When the named template does not exist")
        void missingTemplate() {
            bare.withBean(DataSource.class, () -> mock(DataSource.class))
                    .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                    .withPropertyValues("ecclesiaflow.events.outbox.enabled=true")
                    .run(context -> assertThat(context).hasFailed()
                            .getFailure().rootCause().hasMessageContaining("domainEventsRabbitTemplate"));
        }

        @Test
        @DisplayName("When the template cannot confirm delivery")
        void templateWithoutConfirms() {
            bare.withBean(DataSource.class, () -> mock(DataSource.class))
                    .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                    .withBean("domainEventsRabbitTemplate", RabbitTemplate.class,
                            () -> new RabbitTemplate(mock(ConnectionFactory.class)))
                    .withPropertyValues("ecclesiaflow.events.outbox.enabled=true")
                    .run(context -> assertThat(context).hasFailed()
                            .getFailure().rootCause().hasMessageContaining("publisher-confirm-type=correlated"));
        }

        @Test
        @DisplayName("When signing is configured but the template would relay unsigned")
        void unsignedTemplateWhileSigning() {
            module.withBean(DomainEventSigner.class, () -> new DomainEventSigner("outbox-autoconfig-secret"))
                    .withPropertyValues("ecclesiaflow.events.outbox.enabled=true")
                    .run(context -> assertThat(context).hasFailed()
                            .getFailure().rootCause()
                            .hasMessageContaining(SigningMessagePostProcessor.class.getSimpleName()));
        }

        @Test
        @DisplayName("When a setting is out of range")
        void invalidSetting() {
            module.withPropertyValues("ecclesiaflow.events.outbox.enabled=true",
                            "ecclesiaflow.events.outbox.batch-size=0")
                    .run(context -> assertThat(context).hasFailed()
                            .getFailure().rootCause().hasMessageContaining("batch-size"));
        }
    }
}
