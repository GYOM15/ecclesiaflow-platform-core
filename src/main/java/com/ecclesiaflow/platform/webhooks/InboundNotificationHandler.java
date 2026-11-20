package com.ecclesiaflow.platform.webhooks;

/**
 * The module's reaction to a recorded notification, called apart from the request that brought it and outside
 * any transaction: it reads the object again from the provider, then writes in a short transaction of its own.
 * The same notification may reach it twice (a redelivery under another event id, a lease that ran out), and in
 * any order, so it applies the state it reads, never the notification's.
 */
@FunctionalInterface
public interface InboundNotificationHandler {

    /**
     * @return a short label of what it did, kept on the notification ("APPLIED", "IGNORED"); at most 64 characters
     * @throws RuntimeException to have it tried again later, after a doubling delay, then parked
     */
    String handle(RecordedNotification notification);
}
