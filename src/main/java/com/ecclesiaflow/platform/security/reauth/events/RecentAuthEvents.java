package com.ecclesiaflow.platform.security.reauth.events;

import com.ecclesiaflow.platform.security.reauth.ReauthenticationRequiredException;

import java.util.List;

/**
 * Decisions of the recent sign-in policy, which never logs: listeners turn them into logs. Every {@code subject}
 * is already masked.
 */
public final class RecentAuthEvents {

    private RecentAuthEvents() {
    }

    public record Passed(String subject, long signInAgeSeconds, long maxAgeSeconds) {
    }

    /** A linked account passed on the second factor its token attests. */
    public record SecondFactorPassed(String subject, long signInAgeSeconds, long maxAgeSeconds) {
    }

    /** @param signInAgeSeconds {@code null} when the token gave no sign-in time to measure */
    public record Refused(String subject, ReauthenticationRequiredException.Reason reason, Long signInAgeSeconds,
                          long maxAgeSeconds) {
    }

    public record ConfirmationRequired(String subject, List<String> linkedProviders) {
    }

    /**
     * @param authenticatorUnknown {@code true} when auth did not say whether the account has an authenticator
     *                             app, an auth older than the field or rolled back
     */
    public record EmailCodeRequired(String subject, List<String> linkedProviders, boolean authenticatorUnknown) {
    }

    public record SignInMethodsUnavailable(String subject, Throwable cause) {
    }
}
