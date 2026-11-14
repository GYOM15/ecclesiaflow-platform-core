package com.ecclesiaflow.platform.security.reauth.code;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The challenges and the per-account counts, in the module's own database ({@code db/reauth/reauth_email_code.sql}).
 * Every method runs inside the transaction {@link EmailCodeService} opened, after {@link #lockAccount}.
 */
public interface EmailCodeStore {

    /** Serializes the account's issues, resends and checks until the transaction ends. */
    void lockAccount(String subject);

    /** The account's pending challenge, lapsed or not, locked for update. */
    Optional<EmailCodeChallenge> findOpen(String subject);

    /** Any challenge by its id, whoever it belongs to, locked for update. */
    Optional<EmailCodeChallenge> findForUpdate(UUID challengeId);

    void insert(EmailCodeChallenge challenge);

    /** Writes the code, its counters, its status and when it closed. */
    void update(EmailCodeChallenge challenge);

    /** Pending to consumed, only from pending: {@code false} when another request got there first. */
    boolean consume(UUID challengeId, Instant consumedAt);

    void record(String subject, EmailCodeOccurrence occurrence, UUID challengeId, Instant occurredAt);

    /** Takes back what {@link #record} wrote for this challenge at that instant: a send the mailer refused. */
    void forget(String subject, EmailCodeOccurrence occurrence, UUID challengeId, Instant occurredAt);

    /** The occurrences after {@code since}, and the oldest of them. */
    CounterWindow window(String subject, EmailCodeOccurrence occurrence, Instant since);

    /** Deletes challenges created and occurrences recorded before {@code cutoff}; returns how many rows. */
    int purgeBefore(Instant cutoff);

    /** @param oldest {@code null} when {@code count} is zero */
    record CounterWindow(int count, Instant oldest) {
    }
}
