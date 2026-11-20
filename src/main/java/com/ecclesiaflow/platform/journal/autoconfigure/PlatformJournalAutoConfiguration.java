package com.ecclesiaflow.platform.journal.autoconfigure;

import com.ecclesiaflow.platform.journal.JournalIntegrity;
import com.ecclesiaflow.platform.journal.JournalProperties;
import com.ecclesiaflow.platform.journal.JournalReader;
import com.ecclesiaflow.platform.journal.JournalRepository;
import com.ecclesiaflow.platform.journal.JournalRetention;
import com.ecclesiaflow.platform.journal.PlatformJournal;
import com.ecclesiaflow.platform.journal.actuate.JournalHeadEndpoint;
import com.ecclesiaflow.platform.journal.events.JournalEventListener;
import com.ecclesiaflow.platform.journal.events.JournalMetrics;
import com.ecclesiaflow.platform.journal.jdbc.JdbcJournalReader;
import com.ecclesiaflow.platform.journal.jdbc.JdbcJournalRepository;
import com.ecclesiaflow.platform.journal.jdbc.JdbcPlatformJournal;
import com.ecclesiaflow.platform.journal.jdbc.JournalPrivilegeCheck;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.regex.Pattern;

/**
 * Once enabled it fails the startup rather than run on a journal its role could rewrite: the privilege check runs
 * before any bean can write a line. No bean is conditional on another bean, so the order auto-configurations are
 * processed in cannot change the outcome.
 */
@AutoConfiguration
@ConditionalOnClass(JdbcTemplate.class)
@ConditionalOnProperty(prefix = "ecclesiaflow.journal", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(JournalProperties.class)
public class PlatformJournalAutoConfiguration {

    private static final Pattern MODULE = Pattern.compile("[a-z][a-z0-9-]{0,31}");

    // Private JdbcTemplates: a JdbcTemplate bean would make Boot's own back off in the module.
    @Bean
    public JournalPrivilegeCheck journalPrivilegeCheck(DataSource dataSource) {
        return new JournalPrivilegeCheck(new JdbcTemplate(dataSource));
    }

    @Bean
    @ConditionalOnMissingBean
    public PlatformJournal platformJournal(JournalPrivilegeCheck journalPrivilegeCheck, DataSource dataSource,
                                           ApplicationEventPublisher events, ObjectProvider<Clock> clock) {
        return new JdbcPlatformJournal(new JdbcTemplate(dataSource), new ObjectMapper(), events, clockOf(clock));
    }

    @Bean
    @ConditionalOnMissingBean
    public JournalReader journalReader(JournalPrivilegeCheck journalPrivilegeCheck, DataSource dataSource) {
        return new JdbcJournalReader(new JdbcTemplate(dataSource), new ObjectMapper());
    }

    @Bean
    @ConditionalOnMissingBean
    public JournalRepository journalRepository(JournalPrivilegeCheck journalPrivilegeCheck, DataSource dataSource) {
        return new JdbcJournalRepository(new JdbcTemplate(dataSource), new ObjectMapper());
    }

    @Bean
    @ConditionalOnMissingBean
    public JournalIntegrity journalIntegrity(JournalRepository journalRepository,
                                             PlatformTransactionManager transactionManager,
                                             ApplicationEventPublisher events, ObjectProvider<Clock> clock,
                                             JournalProperties properties) {
        TransactionTemplate snapshot = new TransactionTemplate(transactionManager);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);
        snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new JournalIntegrity(journalRepository, snapshot, events, clockOf(clock), moduleOf(properties));
    }

    @Bean
    @ConditionalOnMissingBean
    public JournalRetention journalRetention(JournalRepository journalRepository, ApplicationEventPublisher events,
                                             JournalProperties properties) {
        return new JournalRetention(journalRepository, events, moduleOf(properties));
    }

    @Bean
    @ConditionalOnMissingBean
    public JournalEventListener journalEventListener() {
        return new JournalEventListener();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    static class JournalMetricsConfiguration {

        /** Without a registry bean the metrics are inert rather than absent, so nothing else changes. */
        @Bean
        @ConditionalOnMissingBean
        JournalMetrics journalMetrics(ObjectProvider<MeterRegistry> registry, JournalProperties properties) {
            return new JournalMetrics(registry.getIfUnique(), moduleOf(properties));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
    static class JournalHeadEndpointConfiguration {

        @Bean
        @ConditionalOnMissingBean
        JournalHeadEndpoint journalHeadEndpoint(JournalIntegrity journalIntegrity, JournalProperties properties) {
            return new JournalHeadEndpoint(journalIntegrity, moduleOf(properties));
        }
    }

    static String moduleOf(JournalProperties properties) {
        String module = properties.getModule();
        if (module == null || !MODULE.matcher(module).matches()) {
            throw new IllegalStateException("ecclesiaflow.journal.module must name the module in lower case (auth, "
                    + "members, church, chat): its metrics and log lines carry it");
        }
        return module;
    }

    private static Clock clockOf(ObjectProvider<Clock> clock) {
        return clock.getIfUnique(Clock::systemUTC);
    }
}
