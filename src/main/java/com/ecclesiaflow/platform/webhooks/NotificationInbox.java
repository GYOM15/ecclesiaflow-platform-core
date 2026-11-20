package com.ecclesiaflow.platform.webhooks;

import java.util.Map;

/**
 * Where a provider's notifications land before anything acts on them: verified first, before any database
 * connection is taken, so a flood of forged bodies holds no connection; then recorded once per provider, account
 * and event id; then handled apart from the request by the module's {@link InboundNotificationHandler}.
 */
public interface NotificationInbox {

    /**
     * @throws InvalidNotificationException when the verifier refuses the body; nothing is then written
     */
    NotificationReceipt receive(NotificationVerifier verifier, byte[] body, Map<String, String> headers);

    /** Makes every parked notification due again from its first try; returns how many. The module traces the act. */
    int replayParked();

    /** Makes one parked or unmatched notification due again from its first try; false when it is neither. */
    boolean replay(long notificationId);
}
