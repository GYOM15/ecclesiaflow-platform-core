package com.ecclesiaflow.platform.time.autoconfigure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformClockAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformClockAutoConfiguration.class));

    @Test
    @DisplayName("a consuming service can inject a UTC Clock without declaring one")
    void providesUtcClock() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(Clock.class);
            assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
        });
    }

    @Test
    @DisplayName("a Clock declared by the service wins, so tests can pin time")
    void backsOffForServiceClock() {
        Clock fixed = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        runner.withBean("fixedClock", Clock.class, () -> fixed)
                .run(context -> {
                    assertThat(context).hasSingleBean(Clock.class);
                    assertThat(context.getBean(Clock.class)).isSameAs(fixed);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class ConsumingApplication {
    }

    @Test
    @DisplayName("the clock is registered through the real auto-configuration import path")
    void registeredThroughImports() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ConsumingApplication.class)
                .web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off")
                .run()) {
            assertThat(context.getBeansOfType(Clock.class)).hasSize(1);
        }
    }
}
