package com.ecclesiaflow.platform.events.outbox.autoconfigure;

import com.ecclesiaflow.platform.events.outbox.OutboxPublisher;
import com.ecclesiaflow.platform.events.outbox.relay.OutboxRelayScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Boots through {@code @EnableAutoConfiguration}, as a consuming service does, so the registration order is real. */
class PlatformOutboxAutoConfigurationImportTest {

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class ModuleWithoutOutbox {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class ModuleWithOutbox {

        @Bean
        DataSource dataSource() {
            return mock(DataSource.class);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return mock(PlatformTransactionManager.class);
        }

        @Bean
        RabbitTemplate domainEventsRabbitTemplate() {
            return PlatformOutboxAutoConfigurationTest.reliableTemplate();
        }
    }

    private static ConfigurableApplicationContext boot(Class<?> application, String... properties) {
        return new SpringApplicationBuilder(application)
                .web(WebApplicationType.NONE)
                .properties(properties)
                .properties("spring.main.banner-mode=off", "spring.sql.init.mode=never")
                .run();
    }

    @Test
    void absentFromAModuleThatDoesNotEnableIt() {
        try (ConfigurableApplicationContext context = boot(ModuleWithoutOutbox.class)) {
            assertThat(context.getBeansOfType(OutboxPublisher.class)).isEmpty();
            assertThat(context.getBeansOfType(OutboxRelayScheduler.class)).isEmpty();
        }
    }

    @Test
    void presentAndRunningInAModuleThatEnablesIt() {
        try (ConfigurableApplicationContext context = boot(ModuleWithOutbox.class,
                "ecclesiaflow.events.outbox.enabled=true",
                "ecclesiaflow.events.outbox.poll-interval=1h")) {
            assertThat(context.getBeansOfType(OutboxPublisher.class)).hasSize(1);
            assertThat(context.getBean(OutboxRelayScheduler.class).isRunning()).isTrue();
        }
    }
}
