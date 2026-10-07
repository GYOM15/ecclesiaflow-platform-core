package com.ecclesiaflow.platform.security.autoconfigure;

import com.ecclesiaflow.platform.security.KeycloakJwtConverter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots through {@code @EnableAutoConfiguration}, the path a consuming service takes.
 */
class PlatformSecurityAutoConfigurationImportTest {

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class ConsumingApplication {
    }

    @Test
    void theConverterIsRegisteredOnTheRealImportPath() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ConsumingApplication.class)
                .web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off")
                .run()) {
            assertThat(context.getBeansOfType(KeycloakJwtConverter.class)).hasSize(1);
        }
    }
}
