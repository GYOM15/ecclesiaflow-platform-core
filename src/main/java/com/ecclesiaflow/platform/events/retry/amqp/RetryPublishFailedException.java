package com.ecclesiaflow.platform.events.retry.amqp;

/** The broker did not take a retry copy: the delivery is dead-lettered rather than acknowledged and lost. */
public class RetryPublishFailedException extends RuntimeException {

    private final String retryQueue;

    public RetryPublishFailedException(String retryQueue, String reason, Throwable cause) {
        super("Retry copy not taken by '" + retryQueue + "': " + reason, cause);
        this.retryQueue = retryQueue;
    }

    public String getRetryQueue() {
        return retryQueue;
    }
}
