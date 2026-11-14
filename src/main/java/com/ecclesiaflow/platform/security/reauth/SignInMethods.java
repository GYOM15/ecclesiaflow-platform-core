package com.ecclesiaflow.platform.security.reauth;

import java.util.List;

/**
 * @param linkedProviders   aliases of the identity providers linked to the account; open, so a value the caller
 *                          does not know still counts as a link
 * @param hasAuthenticator  whether the account holds an authenticator app; {@code null} when auth predates the
 *                          field, which counts as none, never as one
 */
public record SignInMethods(boolean hasPassword, List<String> linkedProviders, Boolean hasAuthenticator) {

    public SignInMethods {
        linkedProviders = linkedProviders == null ? List.of() : List.copyOf(linkedProviders);
    }

    /** What an auth older than {@code has_authenticator} answers. */
    public SignInMethods(boolean hasPassword, List<String> linkedProviders) {
        this(hasPassword, linkedProviders, null);
    }

    public boolean linkedToProvider() {
        return !linkedProviders.isEmpty();
    }

    /** Only an explicit {@code true}: an unknown answer must not send anyone to a sign-in that asks for nothing. */
    public boolean knownToHaveAuthenticator() {
        return Boolean.TRUE.equals(hasAuthenticator);
    }
}
