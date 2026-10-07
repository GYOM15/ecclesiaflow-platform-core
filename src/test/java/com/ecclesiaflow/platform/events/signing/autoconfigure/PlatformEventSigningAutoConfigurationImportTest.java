package com.ecclesiaflow.platform.events.signing.autoconfigure;

import com.ecclesiaflow.platform.events.signing.amqp.SigningMessagePostProcessor;
import com.ecclesiaflow.platform.events.signing.amqp.VerifyingListenerAdvice;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots through {@code @EnableAutoConfiguration}, the path a consuming service takes.
 * {@code ApplicationContextRunner} registers auto-configurations in a different order
 * and cannot see a condition that depends on that order.
 */
class PlatformEventSigningAutoConfigurationImportTest {

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class ConsumingApplication {
    }

    private ConfigurableApplicationContext boot(String... properties) {
        return new SpringApplicationBuilder(ConsumingApplication.class)
                .web(WebApplicationType.NONE)
                .properties(properties)
                .properties("spring.main.banner-mode=off")
                .run();
    }

    @Test
    void amqpHelpersAreRegisteredWhenSigningIsConfigured() {
        try (ConfigurableApplicationContext context = boot(
                "ecclesiaflow.events.hmac-secret=test-secret-at-least-32-bytes-long-xx")) {
            assertThat(context.getBeansOfType(SigningMessagePostProcessor.class)).hasSize(1);
            assertThat(context.getBeansOfType(VerifyingListenerAdvice.class)).hasSize(1);
        }
    }

    @Test
    void amqpHelpersStayAbsentWithoutASecret() {
        try (ConfigurableApplicationContext context = boot()) {
            assertThat(context.getBeansOfType(SigningMessagePostProcessor.class)).isEmpty();
            assertThat(context.getBeansOfType(VerifyingListenerAdvice.class)).isEmpty();
        }
    }
}
