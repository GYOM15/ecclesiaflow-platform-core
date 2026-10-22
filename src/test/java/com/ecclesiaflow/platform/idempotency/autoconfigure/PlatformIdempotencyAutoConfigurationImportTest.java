package com.ecclesiaflow.platform.idempotency.autoconfigure;

import com.ecclesiaflow.platform.idempotency.IdempotentCreator;
import com.ecclesiaflow.platform.idempotency.RecordingTransactionManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;

/** Boots through {@code @EnableAutoConfiguration}, as a consuming service does, so the registration is real. */
class PlatformIdempotencyAutoConfigurationImportTest {

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class ModuleWithTransactions {

        @Bean
        PlatformTransactionManager transactionManager() {
            return new RecordingTransactionManager();
        }
    }

    @Test
    void offersTheCreatorToAModuleWithATransactionManager() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ModuleWithTransactions.class)
                .web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off", "spring.sql.init.mode=never")
                .run()) {
            assertThat(context.getBeansOfType(IdempotentCreator.class)).hasSize(1);
        }
    }
}
