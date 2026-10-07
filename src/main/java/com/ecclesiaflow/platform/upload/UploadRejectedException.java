package com.ecclesiaflow.platform.upload;

/**
 * An upload refused on its own merits, before it is stored; {@link Reason} lets the web layer map it to
 * a stable error without matching messages, which never carry upload bytes. Server faults (a missing
 * ImageIO writer) are {@link IllegalStateException} instead: they are not the uploader's fault.
 */
public class UploadRejectedException extends RuntimeException {

    public enum Reason {
        UNSUPPORTED_TYPE,
        TOO_LARGE,
        TOO_MANY_PIXELS,
        UNREADABLE
    }

    private final Reason reason;

    public UploadRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public UploadRejectedException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
