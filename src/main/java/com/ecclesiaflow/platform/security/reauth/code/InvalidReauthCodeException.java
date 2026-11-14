package com.ecclesiaflow.platform.security.reauth.code;

/** Answered 400 {@code REAUTH_CODE_INVALID}: the dialog keeps the field and the request may be replayed. */
public class InvalidReauthCodeException extends RuntimeException {

    private final boolean counted;

    private InvalidReauthCodeException(String message, boolean counted) {
        super(message);
        this.counted = counted;
    }

    /** A wrong code, counted against the code and the account. */
    public static InvalidReauthCodeException wrongCode() {
        return new InvalidReauthCodeException("The confirmation code does not match", true);
    }

    /** {@code Reauth-Challenge} or {@code Reauth-Code} malformed, or one without the other: not a try, not counted. */
    public static InvalidReauthCodeException malformedProof() {
        return new InvalidReauthCodeException("The confirmation headers are malformed", false);
    }

    public boolean isCounted() {
        return counted;
    }
}
