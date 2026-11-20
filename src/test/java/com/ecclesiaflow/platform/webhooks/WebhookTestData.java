package com.ecclesiaflow.platform.webhooks;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** Notifications as a provider's adapter would hand them over, for the inbox's tests. */
public final class WebhookTestData {

    public static final Instant OCCURRED_AT = Instant.parse("2026-11-20T09:59:58Z");
    public static final byte[] BODY = "{\"id\":\"evt_1\",\"type\":\"payment.paid\"}".getBytes(StandardCharsets.UTF_8);

    private WebhookTestData() {
    }

    public static InboundNotification notification(String eventId, String account, boolean live) {
        return new InboundNotification("acme", account, eventId, "payment.paid", live, "obj_1",
                InboundNotification.digestOf(BODY), OCCURRED_AT, "2026-10-01");
    }

    public static InboundNotification notification(String eventId) {
        return notification(eventId, "acct_1", false);
    }
}
