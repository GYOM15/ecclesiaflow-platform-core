package com.ecclesiaflow.platform.webhooks;

import java.util.Map;

/**
 * Implemented by a provider's adapter, once per notification endpoint, with the provider's own SDK: the library
 * parses no signature scheme, so an anonymous route has one parser, the provider's, and no SDK version of one
 * module is pinned on every other.
 */
public interface NotificationVerifier {

    /** The provider's id the inbox records, a lowercase word ("stripe"). */
    String provider();

    /** The endpoint's label in the metrics: a fixed word ("accounts", "platform"), never a value of the request. */
    String endpoint();

    /**
     * Checks the signature and its age on the body exactly as received, then reads what the notification names.
     *
     * @throws InvalidNotificationException when the signature, its age or the body does not hold
     */
    InboundNotification verify(byte[] body, Map<String, String> headers);
}
