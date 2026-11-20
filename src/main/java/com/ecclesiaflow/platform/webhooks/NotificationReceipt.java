package com.ecclesiaflow.platform.webhooks;

/** What the inbox made of a verified notification. Every one of them is answered alike, so the provider stops. */
public enum NotificationReceipt {

    /** Recorded once, to be handled. */
    RECORDED,
    /** Already recorded under the same provider, account and event id: a redelivery. */
    DUPLICATE,
    /** Of the other mode than the deployment's: a live endpoint receives test notifications too. Not recorded. */
    REFUSED_MODE,
    /** About an account the module does not know: recorded, never handled, until an operator replays it. */
    UNMATCHED
}
