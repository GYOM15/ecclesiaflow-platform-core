package com.ecclesiaflow.platform.security.reauth;

import java.util.List;

/**
 * @param linkedProviders aliases of the identity providers linked to the account; open, so a value the caller
 *                        does not know still counts as a link
 */
public record SignInMethods(boolean hasPassword, List<String> linkedProviders) {

    public SignInMethods {
        linkedProviders = linkedProviders == null ? List.of() : List.copyOf(linkedProviders);
    }

    public boolean linkedToProvider() {
        return !linkedProviders.isEmpty();
    }
}
