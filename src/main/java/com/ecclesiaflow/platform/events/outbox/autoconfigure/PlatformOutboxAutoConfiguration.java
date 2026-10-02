package com.ecclesiaflow.platform.events.outbox.autoconfigure;

import com.ecclesiaflow.platform.events.outbox.OutboxProperties;
import com.ecclesiaflow.platform.events.outbox.OutboxPublisher;
import com.ecclesiaflow.platform.events.outbox.amqp.AmqpOutboxMessageMapper;
import com.ecclesiaflow.platform.events.outbox.amqp.RelayTemplateRequirements;
import com.ecclesiaflow.platform.events.outbox.events.OutboxRelayEventListener;
import com.ecclesiaflow.platform.events.outbox.events.OutboxRelayMetrics;
import com.ecclesiaflow.platform.events.outbox.jdbc.JdbcOutboxPublisher;
import com.ecclesiaflow.platform.events.outbox.jdbc.JdbcOutboxRepository;
import com.ecclesiaflow.platform.events.outbox.relay.OutboxRelay;
import com.ecclesiaflow.platform.events.outbox.relay.OutboxRelayScheduler;
import com.ecclesiaflow.platform.events.outbox.relay.OutboxRepository;
import com.ecclesiaflow.platform.events.outbox.relay.OutboxRetryPolicy;
import com.ecclesiaflow.platform.events.signing.DomainEventSigner;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;

/**
 * Once enabled it fails the startup rather than run half-wired. No bean is conditional on another
 * bean, so the order auto-configurations are processed in cannot change the outcome.
 */
@AutoConfiguration
@ConditionalOnClass({JdbcTemplate.class, RabbitTemplate.class})
@ConditionalOnProperty(prefix = "ecclesiaflow.events.outbox", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(OutboxProperties.class)
public class PlatformOutboxAutoConfiguration {

    /** Grace given to a relay batch in flight at shutdown, beyond its confirm wait. */
    private static final Duration SHUTDOWN_MARGIN = Duration.ofSeconds(10);

    @Bean
    @ConditionalOnMissingBean
    public AmqpOutboxMessageMapper outboxMessageMapper(BeanFactory beanFactory, OutboxProperties properties) {
        return new AmqpOutboxMessageMapper(relayTemplate(beanFactory, properties).getMessageConverter());
    }

    // Private JdbcTemplates: a JdbcTemplate bean would make Boot's own back off in the module.
    @Bean
    @ConditionalOnMissingBean
    public OutboxPublisher outboxPublisher(DataSource dataSource, AmqpOutboxMessageMapper outboxMessageMapper,
                                           ObjectProvider<Clock> clock) {
        return new JdbcOutboxPublisher(new JdbcTemplate(dataSource), outboxMessageMapper, clockOf(clock));
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboxRepository outboxRepository(DataSource dataSource, PlatformTransactionManager transactionManager) {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new JdbcOutboxRepository(new JdbcTemplate(dataSource), transactions);
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboxRelay outboxRelay(OutboxRepository outboxRepository, AmqpOutboxMessageMapper outboxMessageMapper,
                                   BeanFactory beanFactory, ObjectProvider<DomainEventSigner> signer,
                                   ApplicationEventPublisher events, ObjectProvider<Clock> clock,
                                   OutboxProperties properties) {
        RabbitTemplate template = relayTemplate(beanFactory, properties);
        RelayTemplateRequirements.check(template, signer.getIfUnique());
        OutboxRetryPolicy retryPolicy = new OutboxRetryPolicy(properties.getMaxAttempts(),
                properties.getInitialBackoff(), properties.getBackoffMultiplier(), properties.getMaxBackoff());
        OutboxRelay.Settings settings = new OutboxRelay.Settings(properties.getBatchSize(),
                properties.getConfirmTimeout(), properties.getLease());
        return new OutboxRelay(outboxRepository, template, outboxMessageMapper, retryPolicy, settings, events,
                clockOf(clock));
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboxRelayScheduler outboxRelayScheduler(OutboxRelay outboxRelay, OutboxRepository outboxRepository,
                                                     ApplicationEventPublisher events, ObjectProvider<Clock> clock,
                                                     OutboxProperties properties) {
        OutboxRelayScheduler.Schedule schedule = new OutboxRelayScheduler.Schedule(properties.getPollInterval(),
                properties.getPurgeInterval(), properties.getSentRetention(),
                properties.getConfirmTimeout().plus(SHUTDOWN_MARGIN));
        return new OutboxRelayScheduler(outboxRelay, outboxRepository, events, clockOf(clock), schedule);
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboxRelayEventListener outboxRelayEventListener() {
        return new OutboxRelayEventListener();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    static class OutboxMetricsConfiguration {

        /** Without a registry bean the metrics are inert rather than absent, so nothing else changes. */
        @Bean
        @ConditionalOnMissingBean
        OutboxRelayMetrics outboxRelayMetrics(ObjectProvider<MeterRegistry> registry, OutboxRepository outboxRepository,
                                              ObjectProvider<Clock> clock) {
            return new OutboxRelayMetrics(registry.getIfUnique(), outboxRepository, clockOf(clock));
        }
    }

    private static RabbitTemplate relayTemplate(BeanFactory beanFactory, OutboxProperties properties) {
        String name = properties.getRabbitTemplate();
        if (name == null || !beanFactory.containsBean(name)) {
            throw new IllegalStateException("ecclesiaflow.events.outbox.rabbit-template names no bean ('" + name
                    + "'); set it to the RabbitTemplate the module publishes its domain events with");
        }
        return beanFactory.getBean(name, RabbitTemplate.class);
    }

    private static Clock clockOf(ObjectProvider<Clock> clock) {
        return clock.getIfUnique(Clock::systemUTC);
    }
}
