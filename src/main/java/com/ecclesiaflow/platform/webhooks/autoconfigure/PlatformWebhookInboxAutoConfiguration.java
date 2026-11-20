package com.ecclesiaflow.platform.webhooks.autoconfigure;

import com.ecclesiaflow.platform.webhooks.InboundNotificationHandler;
import com.ecclesiaflow.platform.webhooks.NotificationAccountDirectory;
import com.ecclesiaflow.platform.webhooks.NotificationInbox;
import com.ecclesiaflow.platform.webhooks.NotificationVerifier;
import com.ecclesiaflow.platform.webhooks.WebhookInboxProperties;
import com.ecclesiaflow.platform.webhooks.events.WebhookInboxEventListener;
import com.ecclesiaflow.platform.webhooks.events.WebhookInboxMetrics;
import com.ecclesiaflow.platform.webhooks.jdbc.JdbcInboxRepository;
import com.ecclesiaflow.platform.webhooks.jdbc.JdbcNotificationInbox;
import com.ecclesiaflow.platform.webhooks.processing.InboxProcessor;
import com.ecclesiaflow.platform.webhooks.processing.InboxRepository;
import com.ecclesiaflow.platform.webhooks.processing.InboxRetryPolicy;
import com.ecclesiaflow.platform.webhooks.processing.InboxScheduler;
import io.micrometer.core.instrument.MeterRegistry;
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
 * Once enabled it fails the startup rather than run half-wired: without the deployment's mode, the module's
 * account directory or its handler, a notification would be recorded in the wrong mode, for any account, or
 * never handled. No bean is conditional on another bean, so the order auto-configurations are processed in cannot
 * change the outcome.
 */
@AutoConfiguration
@ConditionalOnClass(JdbcTemplate.class)
@ConditionalOnProperty(prefix = "ecclesiaflow.webhooks.inbox", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(WebhookInboxProperties.class)
public class PlatformWebhookInboxAutoConfiguration {

    /** Grace given to a notification being handled at shutdown; one cut short is handed over after its lease. */
    static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(30);

    // Private JdbcTemplates: a JdbcTemplate bean would make Boot's own back off in the module.
    @Bean
    @ConditionalOnMissingBean
    public NotificationInbox notificationInbox(DataSource dataSource, PlatformTransactionManager transactionManager,
                                               ObjectProvider<NotificationAccountDirectory> accounts,
                                               ApplicationEventPublisher events, ObjectProvider<Clock> clock,
                                               WebhookInboxProperties properties) {
        if (properties.getLive() == null) {
            throw new IllegalStateException("ecclesiaflow.webhooks.inbox.live must state the deployment's mode "
                    + "(true for live, false for test)");
        }
        NotificationAccountDirectory directory = accounts.getIfUnique();
        if (directory == null) {
            throw new IllegalStateException("The webhook inbox needs one NotificationAccountDirectory bean: the "
                    + "module says which provider accounts it serves");
        }
        return new JdbcNotificationInbox(new JdbcTemplate(dataSource), requiresNew(transactionManager), directory,
                properties.getLive(), events, clockOf(clock));
    }

    @Bean
    @ConditionalOnMissingBean
    public InboxRepository inboxRepository(DataSource dataSource, PlatformTransactionManager transactionManager) {
        return new JdbcInboxRepository(new JdbcTemplate(dataSource), requiresNew(transactionManager));
    }

    @Bean
    @ConditionalOnMissingBean
    public InboxProcessor inboxProcessor(InboxRepository inboxRepository,
                                         ObjectProvider<InboundNotificationHandler> handler,
                                         ApplicationEventPublisher events, ObjectProvider<Clock> clock,
                                         WebhookInboxProperties properties) {
        InboundNotificationHandler moduleHandler = handler.getIfUnique();
        if (moduleHandler == null) {
            throw new IllegalStateException("The webhook inbox needs one InboundNotificationHandler bean: "
                    + "a recorded notification would never be handled");
        }
        InboxRetryPolicy retryPolicy = new InboxRetryPolicy(properties.getMaxAttempts(),
                properties.getInitialBackoff(), properties.getBackoffMultiplier(), properties.getMaxBackoff());
        InboxProcessor.Settings settings =
                new InboxProcessor.Settings(properties.getBatchSize(), properties.getLease());
        return new InboxProcessor(inboxRepository, moduleHandler, retryPolicy, settings, events, clockOf(clock));
    }

    @Bean
    @ConditionalOnMissingBean
    public InboxScheduler inboxScheduler(InboxProcessor inboxProcessor, ApplicationEventPublisher events,
                                         WebhookInboxProperties properties) {
        return new InboxScheduler(inboxProcessor, events, properties.getPollInterval(), SHUTDOWN_GRACE);
    }

    @Bean
    @ConditionalOnMissingBean
    public WebhookInboxEventListener webhookInboxEventListener() {
        return new WebhookInboxEventListener();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    static class WebhookInboxMetricsConfiguration {

        /** Without a registry bean the metrics are inert rather than absent, so nothing else changes. */
        @Bean
        @ConditionalOnMissingBean
        WebhookInboxMetrics webhookInboxMetrics(ObjectProvider<MeterRegistry> registry, InboxRepository inboxRepository,
                                                ObjectProvider<NotificationVerifier> verifiers,
                                                ObjectProvider<Clock> clock) {
            return new WebhookInboxMetrics(registry.getIfUnique(), inboxRepository,
                    verifiers.orderedStream().toList(), clockOf(clock));
        }
    }

    private static TransactionTemplate requiresNew(PlatformTransactionManager transactionManager) {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transactions;
    }

    private static Clock clockOf(ObjectProvider<Clock> clock) {
        return clock.getIfUnique(Clock::systemUTC);
    }
}
