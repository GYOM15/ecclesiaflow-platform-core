package com.ecclesiaflow.platform.upload;

/**
 * Raised by {@link ImageSanitizer} when every decode slot stayed busy for the whole
 * allowed wait. The upload itself was never examined, so retrying it later is the
 * right answer.
 *
 * <p>Deliberately not an {@link UploadRejectedException}: that one judges the
 * upload on its merits and its callers turn it into a validation error, which would
 * tell a user to change an image that is fine.</p>
 */
public class ImageDecodeCapacityExceededException extends IllegalStateException {

    public ImageDecodeCapacityExceededException(String message) {
        super(message);
    }

    public ImageDecodeCapacityExceededException(String message, Throwable cause) {
        super(message, cause);
    }
}
