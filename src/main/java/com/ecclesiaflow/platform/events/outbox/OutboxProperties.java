package com.ecclesiaflow.platform.events.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Binds {@code ecclesiaflow.events.outbox.*}. With the defaults a message failing at the broker is
 * retried for about half an hour before it is parked; an unreachable broker costs no attempt.
 */
@ConfigurationProperties(prefix = "ecclesiaflow.events.outbox")
public class OutboxProperties {

    /** Turns the outbox on. Off by default: a module that does not opt in is untouched. */
    private boolean enabled = false;

    /** Bean name of the RabbitTemplate the relay publishes with; it must sign, confirm and publish mandatory. */
    private String rabbitTemplate = "domainEventsRabbitTemplate";

    /** Delay between two relay runs when the previous one drained everything that was due. */
    private Duration pollInterval = Duration.ofSeconds(1);

    /** Rows claimed per relay batch. */
    private int batchSize = 50;

    /** How long a batch waits for the broker's publisher confirms. */
    private Duration confirmTimeout = Duration.ofSeconds(5);

    /** How long a claimed row stays out of reach of other relays; must exceed the confirm timeout. */
    private Duration lease = Duration.ofMinutes(2);

    /** Failed broker attempts after which a row is parked for manual replay. */
    private int maxAttempts = 10;

    /** Delay before the first retry, and before a batch deferred because the broker was unreachable. */
    private Duration initialBackoff = Duration.ofSeconds(5);

    /** Growth factor of the retry delay. */
    private double backoffMultiplier = 2.0;

    /** Ceiling of the retry delay. */
    private Duration maxBackoff = Duration.ofMinutes(10);

    /** How long relayed rows are kept before the purge deletes them. Parked rows are never purged. */
    private Duration sentRetention = Duration.ofDays(7);

    /** Delay between two purges of relayed rows. */
    private Duration purgeInterval = Duration.ofHours(1);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getRabbitTemplate() {
        return rabbitTemplate;
    }

    public void setRabbitTemplate(String rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
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

    public Duration getConfirmTimeout() {
        return confirmTimeout;
    }

    public void setConfirmTimeout(Duration confirmTimeout) {
        this.confirmTimeout = confirmTimeout;
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

    public Duration getSentRetention() {
        return sentRetention;
    }

    public void setSentRetention(Duration sentRetention) {
        this.sentRetention = sentRetention;
    }

    public Duration getPurgeInterval() {
        return purgeInterval;
    }

    public void setPurgeInterval(Duration purgeInterval) {
        this.purgeInterval = purgeInterval;
    }
}
