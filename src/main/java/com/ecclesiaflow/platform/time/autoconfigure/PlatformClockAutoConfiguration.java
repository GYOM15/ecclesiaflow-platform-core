package com.ecclesiaflow.platform.time.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * Injected rather than {@code now()} so time-dependent code can be pinned in tests.
 */
@AutoConfiguration
public class PlatformClockAutoConfiguration {

    // UTC rather than the host zone: the Pi and the containers do not share one.
    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
