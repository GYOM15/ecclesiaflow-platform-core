package com.ecclesiaflow.platform.security.reauth;

/**
 * Answered 403 {@code REAUTH_REQUIRED}: the client signs in again ({@code prompt=login}, {@code max_age=0}) and
 * replays the gesture once.
 */
public class ReauthenticationRequiredException extends RuntimeException {

    public enum Reason {
        /** No authenticated token names an account: there is no sign-in to judge. */
        NO_USER_TOKEN,
        /** The token says a provider brokered the sign-in, which a provider can do silently. */
        BROKERED_SIGN_IN,
        /** The token does not say when the user signed in. */
        NO_SIGN_IN_TIME,
        SIGN_IN_TOO_OLD
    }

    private final Reason reason;

    private ReauthenticationRequiredException(Reason reason) {
        super("A recent sign-in is required: " + reason);
        this.reason = reason;
    }

    public static ReauthenticationRequiredException noUserToken() {
        return new ReauthenticationRequiredException(Reason.NO_USER_TOKEN);
    }

    public static ReauthenticationRequiredException brokeredSignIn() {
        return new ReauthenticationRequiredException(Reason.BROKERED_SIGN_IN);
    }

    public static ReauthenticationRequiredException noSignInTime() {
        return new ReauthenticationRequiredException(Reason.NO_SIGN_IN_TIME);
    }

    public static ReauthenticationRequiredException signInTooOld() {
        return new ReauthenticationRequiredException(Reason.SIGN_IN_TOO_OLD);
    }

    public Reason getReason() {
        return reason;
    }
}
