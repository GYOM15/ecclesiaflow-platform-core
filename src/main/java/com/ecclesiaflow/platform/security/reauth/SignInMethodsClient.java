package com.ecclesiaflow.platform.security.reauth;

/**
 * The account's sign-in methods, which the module reads from auth ({@code GetSignInMethods}). A fact of the
 * account, not of the session: an account linked to Google that signed in with its password carries no
 * {@code identity_provider} note, yet Google can still sign it back in silently.
 *
 * <p>Throws when it cannot answer, within a deadline the adapter sets: the gesture is then refused 503, never
 * let through.</p>
 */
public interface SignInMethodsClient {

    SignInMethods find(String keycloakUserId);
}
