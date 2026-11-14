package com.ecclesiaflow.platform.security.reauth.code.events;

import java.util.UUID;

/**
 * Decisions of the e-mail code, which never logs: listeners turn them into logs and counts. A {@code subject} or a
 * challenge id is raw, the listener masks it; no event ever carries a code, an address or a digest.
 */
public final class EmailCodeEvents {

    private EmailCodeEvents() {
    }

    /** @param renewed {@code true} for a new code on a challenge already open, a resend or a lapsed code */
    public record Sent(String subject, UUID challengeId, String action, boolean renewed) {
    }

    /** The same gesture again while its code is still valid: the same 202, nothing sent. */
    public record Reused(String subject, UUID challengeId, String action) {
    }

    public enum NotSentReason { NO_ADDRESS, DELIVERY_FAILED }

    /** @param cause {@code null} for {@link NotSentReason#NO_ADDRESS} */
    public record NotSent(String subject, String action, NotSentReason reason, Throwable cause) {
    }

    /** @param replayed {@code true} when a consumed challenge bound to an Idempotency-Key passed again */
    public record Verified(String subject, UUID challengeId, String action, boolean replayed) {
    }

    /** A wrong code: the code's count and the account's, after this one. */
    public record Invalid(String subject, UUID challengeId, int codeFailures, int accountFailures) {
    }

    /** Headers that do not read as a challenge and a code; not a try, not counted. */
    public record Malformed(String subject, String action) {
    }

    public enum ExpiryReason {
        /** No challenge of this account has that id. */
        UNKNOWN,
        /** Consumed without a key, replaced or voided. */
        CLOSED,
        /** The code's ten minutes, or the challenge's thirty, are over. */
        LAPSED,
        /** Five wrong codes. */
        WORN_OUT
    }

    public record Expired(String subject, UUID challengeId, ExpiryReason reason) {
    }

    public record Locked(String subject, long retryAfterSeconds) {
    }

    public enum SupersededBy {
        /** Another gesture of the account. */
        NEW_GESTURE,
        /** The challenge was presented with a request other than its own: not a try at the code, not counted. */
        OTHER_REQUEST
    }

    public record Superseded(String subject, UUID challengeId, SupersededBy by) {
    }

    /** A code refused before it was drawn: the resend delay or a sending cap. */
    public record SendThrottled(String subject, String rule, long retryAfterSeconds) {
    }
}
