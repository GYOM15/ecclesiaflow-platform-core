package com.ecclesiaflow.platform.security.reauth.code;

import java.util.Objects;

/**
 * A gesture to confirm by a code sent to {@code address}.
 *
 * @param subject       the {@code sub} of the token that asked
 * @param action        the gesture's name
 * @param gesture       the closed label the e-mail names it with
 * @param bindingDigest the keyed digest of the request the code will be worth
 * @param keyed         whether the request carries an {@code Idempotency-Key}
 * @param address       the account's current address, from the token's {@code email} claim
 * @param locale        the token's {@code locale} claim, {@code null} when it has none
 */
public record EmailCodeIssue(String subject, String action, String gesture, String bindingDigest, boolean keyed,
                             String address, String locale) {

    public EmailCodeIssue {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(gesture, "gesture");
        Objects.requireNonNull(bindingDigest, "bindingDigest");
    }

    @Override
    public String toString() {
        return "EmailCodeIssue[action=" + action + ", gesture=" + gesture + ", keyed=" + keyed + "]";
    }
}
