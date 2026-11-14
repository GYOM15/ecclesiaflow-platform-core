package com.ecclesiaflow.platform.security.reauth.code;

/**
 * Answered 503 {@code SERVICE_UNAVAILABLE}: no code left, so nothing may say one did. Its challenge is voided and
 * the send is not counted.
 */
public class ReauthCodeNotSentException extends RuntimeException {

    private ReauthCodeNotSentException(String message, Throwable cause) {
        super(message, cause);
    }

    /** The token carries no {@code email} claim: there is nowhere to send the code. */
    public static ReauthCodeNotSentException noAddress() {
        return new ReauthCodeNotSentException("The token names no address to send the code to", null);
    }

    public static ReauthCodeNotSentException deliveryFailed(Throwable cause) {
        return new ReauthCodeNotSentException("The confirmation code could not be sent", cause);
    }
}
