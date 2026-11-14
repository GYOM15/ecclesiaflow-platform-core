package com.ecclesiaflow.platform.security.reauth;

/**
 * What proves, for an account linked to an identity provider, that its holder makes the gesture. An account
 * without a provider link is judged on its sign-in alone, whatever the mode.
 */
public enum LinkedAccountProof {

    /** A link sent to the account's current address, which the module's {@link PendingConfirmationIssuer} holds. */
    CONFIRMATION_LINK,

    /**
     * A second factor that Keycloak attests in the token ({@code amr} holding {@code otp} or {@code recovery-code},
     * sign-in at most the gesture's age old); else a new sign-in when the account has an authenticator app; else
     * a six-digit code sent to the account's address, which the request replays in {@code Reauth-Challenge} and
     * {@code Reauth-Code}.
     */
    SECOND_FACTOR_OR_EMAIL_CODE
}
