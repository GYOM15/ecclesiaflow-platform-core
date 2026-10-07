package com.ecclesiaflow.platform.security.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots through {@code @EnableAutoConfiguration}, the path a consuming service takes.
 */
class PlatformRestJwtDecoderAutoConfigurationImportTest {

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class ConsumingApplication {
    }

    @Test
    void theDecoderIsRegisteredOnTheRealImportPath() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ConsumingApplication.class)
                .web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off",
                        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://kc.example/realms/ecclesiaflow",
                        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://kc.example/certs")
                .run()) {
            assertThat(context.getBeansOfType(JwtDecoder.class)).containsOnlyKeys("restJwtDecoder");
        }
    }
}
