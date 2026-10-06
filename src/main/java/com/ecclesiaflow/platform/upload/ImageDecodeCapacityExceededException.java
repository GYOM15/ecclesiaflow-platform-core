package com.ecclesiaflow.platform.upload;

/**
 * Every decode slot stayed busy for the allowed wait; the upload was never examined, so retry later.
 * Not an {@link UploadRejectedException}: that one becomes a validation error, which would tell a user
 * to change an image that is fine.
 */
public class ImageDecodeCapacityExceededException extends IllegalStateException {

    private final long retryAfterSeconds;

    public ImageDecodeCapacityExceededException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public ImageDecodeCapacityExceededException(String message, long retryAfterSeconds, Throwable cause) {
        super(message, cause);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
