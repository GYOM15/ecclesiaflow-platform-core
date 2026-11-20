package com.ecclesiaflow.platform.webhooks;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Binds {@code ecclesiaflow.webhooks.inbox.*}. With the defaults a notification whose handling keeps failing is
 * tried again after 30 seconds, then after doubling delays up to an hour, and parked at its tenth failure, about
 * five hours after it came.
 */
@ConfigurationProperties(prefix = "ecclesiaflow.webhooks.inbox")
public class WebhookInboxProperties {

    /** Turns the inbox on. Off by default: a module that does not opt in is untouched. */
    private boolean enabled = false;

    /**
     * The deployment's mode, true for live: a notification of the other mode is answered and never recorded.
     * No default, so a deployment never runs in a mode it did not state.
     */
    private Boolean live;

    /** Delay between two processing runs when the previous one drained everything that was due. */
    private Duration pollInterval = Duration.ofSeconds(2);

    private int batchSize = 10;

    /** How long a claimed notification stays out of reach of other processors; must outlast a batch's handling. */
    private Duration lease = Duration.ofMinutes(5);

    /** Failed handlings in a row after which a notification is parked for an operator. */
    private int maxAttempts = 10;

    private Duration initialBackoff = Duration.ofSeconds(30);

    private double backoffMultiplier = 2.0;

    private Duration maxBackoff = Duration.ofHours(1);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Boolean getLive() {
        return live;
    }

    public void setLive(Boolean live) {
        this.live = live;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public Duration getLease() {
        return lease;
    }

    public void setLease(Duration lease) {
        this.lease = lease;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getInitialBackoff() {
        return initialBackoff;
    }

    public void setInitialBackoff(Duration initialBackoff) {
        this.initialBackoff = initialBackoff;
    }

    public double getBackoffMultiplier() {
        return backoffMultiplier;
    }

    public void setBackoffMultiplier(double backoffMultiplier) {
        this.backoffMultiplier = backoffMultiplier;
    }

    public Duration getMaxBackoff() {
        return maxBackoff;
    }

    public void setMaxBackoff(Duration maxBackoff) {
        this.maxBackoff = maxBackoff;
    }
}
