package com.ecclesiaflow.platform.webhooks;

/**
 * The module says which provider accounts it serves, from its own records, never from the notification's
 * metadata: an account that creates its own objects must not reach another's records.
 */
@FunctionalInterface
public interface NotificationAccountDirectory {

    /** @param account the provider account; empty for the provider's own (platform) scope */
    boolean knows(String provider, String account);
}
