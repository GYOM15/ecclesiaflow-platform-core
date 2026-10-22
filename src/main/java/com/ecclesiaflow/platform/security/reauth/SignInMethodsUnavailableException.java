package com.ecclesiaflow.platform.security.reauth;

/**
 * Answered 503 {@code SERVICE_UNAVAILABLE}, never 403 {@code REAUTH_REQUIRED}: a fresh sign-in would not help,
 * and without the account's sign-in methods the gesture can neither pass nor be sent for confirmation.
 */
public class SignInMethodsUnavailableException extends RuntimeException {

    public SignInMethodsUnavailableException(Throwable cause) {
        super("The account's sign-in methods could not be read", cause);
    }
}
