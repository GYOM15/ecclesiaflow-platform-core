package com.ecclesiaflow.platform.events.retry;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Binds {@code ecclesiaflow.events.delayed-retry.*}; delays are per topology, in code. */
@ConfigurationProperties(prefix = "ecclesiaflow.events.delayed-retry")
public class DelayedRetryProperties {

    /** How long the recoverer waits for the broker to confirm a copy before dead-lettering the delivery. */
    private Duration confirmTimeout = Duration.ofSeconds(5);

    public Duration getConfirmTimeout() {
        return confirmTimeout;
    }

    public void setConfirmTimeout(Duration confirmTimeout) {
        this.confirmTimeout = confirmTimeout;
    }
}
