package com.ecclesiaflow.platform.webhooks.autoconfigure;

import com.ecclesiaflow.platform.webhooks.InboundNotificationHandler;
import com.ecclesiaflow.platform.webhooks.NotificationAccountDirectory;
import com.ecclesiaflow.platform.webhooks.NotificationInbox;
import com.ecclesiaflow.platform.webhooks.WebhookInboxProperties;
import com.ecclesiaflow.platform.webhooks.events.WebhookInboxEventListener;
import com.ecclesiaflow.platform.webhooks.events.WebhookInboxMetrics;
import com.ecclesiaflow.platform.webhooks.jdbc.JdbcNotificationInbox;
import com.ecclesiaflow.platform.webhooks.processing.InboxProcessor;
import com.ecclesiaflow.platform.webhooks.processing.InboxRepository;
import com.ecclesiaflow.platform.webhooks.processing.InboxScheduler;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PlatformWebhookInboxAutoConfigurationTest {

    private final ApplicationContextRunner bare = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformWebhookInboxAutoConfiguration.class));

    /** A module with a database, its account directory and its handler, as the payments service has. */
    private final ApplicationContextRunner module = bare
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(NotificationAccountDirectory.class, () -> (provider, account) -> true)
            .withBean(InboundNotificationHandler.class, () -> notification -> "APPLIED")
            // Keep the scheduler from polling the mocked DataSource while the context is up.
            .withPropertyValues("ecclesiaflow.webhooks.inbox.poll-interval=1h");

    private final ApplicationContextRunner enabled = module.withPropertyValues(
            "ecclesiaflow.webhooks.inbox.enabled=true", "ecclesiaflow.webhooks.inbox.live=false");

    @Test
    @DisplayName("is registered for Spring Boot to find")
    void registered() {
        List<String> candidates = ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader())
                .getCandidates();

        assertThat(candidates).contains(PlatformWebhookInboxAutoConfiguration.class.getName());
    }

    @Nested
    @DisplayName("not enabled")
    class NotEnabled {

        @Test
        @DisplayName("contributes nothing to a module that does not enable it")
        void inertByDefault() {
            module.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(NotificationInbox.class);
                assertThat(context).doesNotHaveBean(InboxScheduler.class);
                assertThat(context).doesNotHaveBean(WebhookInboxProperties.class);
            });
        }

        @Test
        @DisplayName("asks nothing of a module without a database, a directory or a handler")
        void inertWithoutInfrastructure() {
            bare.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(NotificationInbox.class));
        }

        @Test
        @DisplayName("stays out of a module without Spring JDBC")
        void inertWithoutJdbc() {
            bare.withClassLoader(new FilteredClassLoader(JdbcTemplate.class))
                    .withPropertyValues("ecclesiaflow.webhooks.inbox.enabled=true")
                    .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(NotificationInbox.class));
        }
    }

    @Nested
    @DisplayName("enabled")
    class Enabled {

        @Test
        @DisplayName("wires the inbox, its processor, its scheduler and its listener, the scheduler running")
        void wiresEverything() {
            enabled.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(NotificationInbox.class)).isInstanceOf(JdbcNotificationInbox.class);
                assertThat(context).hasSingleBean(InboxRepository.class);
                assertThat(context).hasSingleBean(InboxProcessor.class);
                assertThat(context).hasSingleBean(WebhookInboxEventListener.class);
                assertThat(context).hasSingleBean(WebhookInboxMetrics.class);
                assertThat(context.getBean(InboxScheduler.class).isRunning()).isTrue();
            });
        }

        @Test
        @DisplayName("measures into the module's registry")
        void measures() {
            enabled.withBean(MeterRegistry.class, SimpleMeterRegistry::new).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(MeterRegistry.class).find(WebhookInboxMetrics.PENDING_METRIC).gauge())
                        .isNotNull();
            });
        }

        @Test
        @DisplayName("backs off for an inbox the module declares itself")
        void moduleInboxWins() {
            NotificationInbox own = mock(NotificationInbox.class);
            enabled.withBean(NotificationInbox.class, () -> own)
                    .run(context -> assertThat(context.getBean(NotificationInbox.class)).isSameAs(own));
        }

        @Test
        @DisplayName("binds every setting of the inbox")
        void bindsEverySetting() {
            enabled.withPropertyValues("ecclesiaflow.webhooks.inbox.batch-size=25",
                    "ecclesiaflow.webhooks.inbox.lease=PT10M", "ecclesiaflow.webhooks.inbox.max-attempts=5",
                    "ecclesiaflow.webhooks.inbox.initial-backoff=PT1M",
                    "ecclesiaflow.webhooks.inbox.backoff-multiplier=3",
                    "ecclesiaflow.webhooks.inbox.max-backoff=PT2H").run(context -> {
                        WebhookInboxProperties properties = context.getBean(WebhookInboxProperties.class);
                        assertThat(properties.isEnabled()).isTrue();
                        assertThat(properties.getLive()).isFalse();
                        assertThat(properties.getPollInterval()).isEqualTo(Duration.ofHours(1));
                        assertThat(properties.getBatchSize()).isEqualTo(25);
                        assertThat(properties.getLease()).isEqualTo(Duration.ofMinutes(10));
                        assertThat(properties.getMaxAttempts()).isEqualTo(5);
                        assertThat(properties.getInitialBackoff()).isEqualTo(Duration.ofMinutes(1));
                        assertThat(properties.getBackoffMultiplier()).isEqualTo(3.0);
                        assertThat(properties.getMaxBackoff()).isEqualTo(Duration.ofHours(2));
                    });
        }

        @Test
        @DisplayName("fails the startup when the deployment's mode is not stated")
        void needsTheMode() {
            module.withPropertyValues("ecclesiaflow.webhooks.inbox.enabled=true")
                    .run(context -> assertThat(context).hasFailed().getFailure()
                            .rootCause().hasMessageContaining("ecclesiaflow.webhooks.inbox.live"));
        }

        @Test
        @DisplayName("fails the startup without the module's account directory")
        void needsTheDirectory() {
            bare.withBean(DataSource.class, () -> mock(DataSource.class))
                    .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                    .withBean(InboundNotificationHandler.class, () -> notification -> "APPLIED")
                    .withPropertyValues("ecclesiaflow.webhooks.inbox.enabled=true",
                            "ecclesiaflow.webhooks.inbox.live=false", "ecclesiaflow.webhooks.inbox.poll-interval=1h")
                    .run(context -> assertThat(context).hasFailed().getFailure()
                            .rootCause().hasMessageContaining("NotificationAccountDirectory"));
        }

        @Test
        @DisplayName("fails the startup without the module's handler")
        void needsTheHandler() {
            bare.withBean(DataSource.class, () -> mock(DataSource.class))
                    .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                    .withBean(NotificationAccountDirectory.class, () -> (provider, account) -> true)
                    .withPropertyValues("ecclesiaflow.webhooks.inbox.enabled=true",
                            "ecclesiaflow.webhooks.inbox.live=false", "ecclesiaflow.webhooks.inbox.poll-interval=1h")
                    .run(context -> assertThat(context).hasFailed().getFailure()
                            .rootCause().hasMessageContaining("InboundNotificationHandler"));
        }

        @Test
        @DisplayName("fails the startup on retry settings that make no sense")
        void refusesBadSettings() {
            enabled.withPropertyValues("ecclesiaflow.webhooks.inbox.max-attempts=0")
                    .run(context -> assertThat(context).hasFailed().getFailure()
                            .rootCause().hasMessageContaining("max-attempts"));
        }
    }
}
