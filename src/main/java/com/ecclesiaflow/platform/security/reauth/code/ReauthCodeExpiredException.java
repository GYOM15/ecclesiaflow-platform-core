package com.ecclesiaflow.platform.security.reauth.code;

/**
 * Answered 410 {@code REAUTH_CODE_EXPIRED}, one answer for a challenge unknown, another account's, expired,
 * replaced, worn out, voided or already used: nothing tells them apart.
 */
public class ReauthCodeExpiredException extends RuntimeException {

    public ReauthCodeExpiredException() {
        super("The confirmation code is no longer valid");
    }
}
