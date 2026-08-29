package com.ecclesiaflow.platform.upload;

/**
 * Unchecked failure raised by {@link ImageSanitizer} / {@link FileSanitizer}
 * when a user-supplied upload is rejected <em>before</em> it is ever stored.
 *
 * <p>Every rejection carries a machine-readable {@link Reason} so a module's web
 * layer can map it to a stable API error / HTTP status (typically 400/413/415)
 * without string-matching a message. Messages are safe to log but deliberately
 * carry no upload bytes.</p>
 *
 * <p>Note: a rejection means the upload was refused on its own (hostile or
 * unsupported) merits. It does <strong>not</strong> cover server-side faults
 * such as a missing ImageIO writer — those surface as
 * {@link IllegalStateException}, because they are not the uploader's fault.</p>
 */
public class UploadRejectedException extends RuntimeException {

    /**
     * Why an upload was refused.
     *
     * <ul>
     *   <li>{@link #UNSUPPORTED_TYPE} — the sniffed media type is not on the
     *       policy's allow-list (the client-declared type is ignored entirely).</li>
     *   <li>{@link #TOO_LARGE} — the raw byte length exceeds the policy cap.</li>
     *   <li>{@link #TOO_MANY_PIXELS} — declared width × height exceeds the policy
     *       cap; the decompression-bomb guard, checked <em>before</em> full
     *       decode so a tiny file can never inflate into a huge raster.</li>
     *   <li>{@link #UNREADABLE} — the bytes passed the type check but could not be
     *       decoded into an image (truncated, corrupt, or a spoofed header).</li>
     * </ul>
     */
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

    /** @return the machine-readable rejection reason. */
    public Reason getReason() {
        return reason;
    }
}
