package com.ecclesiaflow.platform.security.reauth.code;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One gesture waiting for its code, as stored: digests only, never the code or the address.
 *
 * @param subject       the {@code sub} of the account; the caps are counted on it
 * @param action        the gesture's name, the contract label of its operation
 * @param gesture       the closed label the e-mail names the gesture with, kept for a resend
 * @param bindingDigest the keyed digest of the exact request the code is worth
 * @param keyed         whether that request carried an {@code Idempotency-Key}: then the same request replays
 *                      with the same code until the code expires
 * @param codeDigest    the keyed digest of the code; {@code null} once {@link EmailCodeStatus#VOID}
 * @param codeFailures  wrong codes against the current code
 * @param closedAt      when the challenge left {@link EmailCodeStatus#PENDING}
 */
public record EmailCodeChallenge(UUID id, String subject, String action, String gesture, String bindingDigest,
                                 boolean keyed, String codeDigest, int codeFailures, Instant codeIssuedAt,
                                 Instant codeExpiresAt, Instant resendAvailableAt, EmailCodeStatus status,
                                 Instant createdAt, Instant expiresAt, Instant closedAt) {

    public EmailCodeChallenge {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(bindingDigest, "bindingDigest");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /** A new challenge with its first code, issued now. */
    public static EmailCodeChallenge open(UUID id, EmailCodeIssue issue, String codeDigest, Instant now) {
        Instant expiresAt = now.plus(EmailCodeLimits.CHALLENGE_LIFETIME);
        return new EmailCodeChallenge(id, issue.subject(), issue.action(), issue.gesture(), issue.bindingDigest(),
                issue.keyed(), codeDigest, 0, now, codeEnd(now, expiresAt), now.plus(EmailCodeLimits.RESEND_DELAY),
                EmailCodeStatus.PENDING, now, expiresAt, null);
    }

    /** Pending and within its 30 minutes. */
    public boolean openAt(Instant now) {
        return status == EmailCodeStatus.PENDING && now.isBefore(expiresAt);
    }

    /** Its code may still be presented: sent, unexpired, not worn out, within the challenge's life. */
    public boolean codeLiveAt(Instant now) {
        return codeDigest != null && codeFailures < EmailCodeLimits.FAILURES_PER_CODE
                && codeExpiresAt != null && now.isBefore(codeExpiresAt) && now.isBefore(expiresAt);
    }

    public boolean codeUsableAt(Instant now) {
        return openAt(now) && codeLiveAt(now);
    }

    /** The next code: its own five tries, the account's count untouched. */
    public EmailCodeChallenge withNewCode(String newCodeDigest, Instant now) {
        return new EmailCodeChallenge(id, subject, action, gesture, bindingDigest, keyed, newCodeDigest, 0, now,
                codeEnd(now, expiresAt), now.plus(EmailCodeLimits.RESEND_DELAY), status, createdAt, expiresAt,
                closedAt);
    }

    public EmailCodeChallenge withFailedCode() {
        return new EmailCodeChallenge(id, subject, action, gesture, bindingDigest, keyed, codeDigest,
                codeFailures + 1, codeIssuedAt, codeExpiresAt, resendAvailableAt, status, createdAt, expiresAt,
                closedAt);
    }

    public EmailCodeChallenge superseded(Instant now) {
        return closed(EmailCodeStatus.SUPERSEDED, codeDigest, now);
    }

    public EmailCodeChallenge voided(Instant now) {
        return closed(EmailCodeStatus.VOID, null, now);
    }

    public EmailCodeChallenge consumed(Instant now) {
        return closed(EmailCodeStatus.CONSUMED, codeDigest, now);
    }

    private EmailCodeChallenge closed(EmailCodeStatus closedStatus, String keptDigest, Instant now) {
        return new EmailCodeChallenge(id, subject, action, gesture, bindingDigest, keyed, keptDigest, codeFailures,
                codeIssuedAt, codeExpiresAt, resendAvailableAt, closedStatus, createdAt, expiresAt, now);
    }

    // A code never outlives its challenge: past 30 minutes the gesture starts again.
    private static Instant codeEnd(Instant now, Instant challengeEnd) {
        Instant end = now.plus(EmailCodeLimits.CODE_LIFETIME);
        return end.isAfter(challengeEnd) ? challengeEnd : end;
    }

    // Digests and the subject stay out of any text a log or an assertion message could print.
    @Override
    public String toString() {
        return "EmailCodeChallenge[id=" + id + ", action=" + action + ", status=" + status + ", codeFailures="
                + codeFailures + "]";
    }
}
