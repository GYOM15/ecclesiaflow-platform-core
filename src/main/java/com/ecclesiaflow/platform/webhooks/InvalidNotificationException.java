package com.ecclesiaflow.platform.webhooks;

/**
 * A body the provider did not sign, signed too long ago, or that cannot be read once signed. Nothing of it is
 * recorded, and the route answers 400 whatever the reason, so a forger learns nothing from the answer.
 */
public class InvalidNotificationException extends RuntimeException {

    public enum Reason {
        /** Missing, forged, or outside the accepted age. */
        SIGNATURE,
        /** Signed, but it names no event the inbox can record. */
        MALFORMED
    }

    private final Reason reason;

    private InvalidNotificationException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public static InvalidNotificationException badSignature(Throwable cause) {
        return new InvalidNotificationException(Reason.SIGNATURE, "The notification's signature does not hold", cause);
    }

    public static InvalidNotificationException malformed(Throwable cause) {
        return new InvalidNotificationException(Reason.MALFORMED, "The signed notification cannot be read", cause);
    }

    public Reason reason() {
        return reason;
    }
}
