package com.ecclesiaflow.platform.events.sealing;

/** Carries only its reason: the message ends up in logs, and the value it refused is a secret. */
public class SealedFieldUnreadableException extends RuntimeException {

    public enum Reason {
        /** Sealed under a key this service does not hold: retired too early, or not deployed here yet. */
        UNKNOWN_KEY,
        /** Too short to hold a nonce and a tag, or no event id to check it against. */
        MALFORMED,
        /** The tag does not match: altered bytes, another event's id, or other key material under that id. */
        NOT_AUTHENTIC
    }

    private final Reason reason;

    public SealedFieldUnreadableException(Reason reason) {
        super("Sealed field cannot be opened: " + reason);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
