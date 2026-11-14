package com.ecclesiaflow.platform.security.reauth.code;

import com.ecclesiaflow.platform.ratelimit.RateLimitExceededException;
import com.ecclesiaflow.platform.security.reauth.PendingConfirmation;
import com.ecclesiaflow.platform.security.reauth.code.events.EmailCodeEvents;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A six-digit code that confirms one request of a linked account without an authenticator app (PLAN-REAUTH-CODE
 * §4): issued, resent and checked under a lock per account, its state committed before any answer, and the
 * consumption committed before the gesture starts. The mail leaves outside every transaction; a refused mail voids
 * its challenge and costs no send.
 *
 * <p>Each transaction is a new one: the gesture's own transactions never hold these rows.</p>
 */
public class EmailCodeService {

    static final String RESEND_RULE = "reauth-email-code-resend";
    static final String HOUR_RULE = "reauth-email-code-hour";
    static final String DAY_RULE = "reauth-email-code-day";

    private final EmailCodeStore store;
    private final EmailCodeSecrets secrets;
    private final EmailCodeMailer mailer;
    private final TransactionOperations transactions;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public EmailCodeService(EmailCodeStore store, EmailCodeSecrets secrets, EmailCodeMailer mailer,
                            PlatformTransactionManager transactionManager, ApplicationEventPublisher events,
                            Clock clock) {
        this(store, secrets, mailer, requiresNew(transactionManager), events, clock);
    }

    EmailCodeService(EmailCodeStore store, EmailCodeSecrets secrets, EmailCodeMailer mailer,
                     TransactionOperations transactions, ApplicationEventPublisher events, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.mailer = Objects.requireNonNull(mailer, "mailer");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Sends a code for this gesture, or answers the same 202 without sending when the account's open challenge is
     * this very request and its code is still valid. Another gesture replaces the open challenge.
     *
     * @return the 202 body
     * @throws ReauthCodeLockedException   after ten wrong codes in 24 hours
     * @throws RateLimitExceededException  within 60 s of the last code of this request, or past 3 codes an hour or
     *                                     10 a day
     * @throws ReauthCodeNotSentException  when there is no address or the mailer refuses: nothing was sent
     */
    public PendingConfirmation issue(EmailCodeIssue request) {
        Objects.requireNonNull(request, "request");
        refuseWithoutAddress(request.subject(), request.action(), request.address());
        String code = secrets.newCode();
        Instant now = clock.instant();
        Issued issued = transactions.execute(status -> issueLocked(request, code, now));
        if (issued.superseded() != null) {
            events.publishEvent(new EmailCodeEvents.Superseded(request.subject(), issued.superseded().id(),
                    EmailCodeEvents.SupersededBy.NEW_GESTURE));
        }
        if (issued.reused()) {
            events.publishEvent(new EmailCodeEvents.Reused(request.subject(), issued.challenge().id(),
                    request.action()));
        } else {
            deliver(issued.challenge(), code, request.address(), request.locale(), issued.renewed());
        }
        return body(issued.challenge(), request.address());
    }

    /**
     * A new code on the account's open challenge, the old one dead at once.
     *
     * @throws ReauthCodeExpiredException  when the challenge is unknown, another account's, closed or lapsed
     * @throws ReauthCodeLockedException   after ten wrong codes in 24 hours
     * @throws RateLimitExceededException  within 60 s of the last code, or past a sending cap
     * @throws ReauthCodeNotSentException  when there is no address or the mailer refuses: the challenge is voided
     */
    public PendingConfirmation resend(String subject, UUID challengeId, String address, String locale) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(challengeId, "challengeId");
        String action = "resend";
        refuseWithoutAddress(subject, action, address);
        String code = secrets.newCode();
        Instant now = clock.instant();
        EmailCodeChallenge renewed = transactions.execute(status -> {
            store.lockAccount(subject);
            Optional<EmailCodeChallenge> found = store.findForUpdate(challengeId)
                    .filter(challenge -> challenge.subject().equals(subject));
            refuseIfLocked(subject, now);
            EmailCodeChallenge challenge = found.orElseThrow(() -> expired(subject, challengeId,
                    EmailCodeEvents.ExpiryReason.UNKNOWN));
            if (challenge.status() != EmailCodeStatus.PENDING) {
                throw expired(subject, challengeId, EmailCodeEvents.ExpiryReason.CLOSED);
            }
            if (!challenge.openAt(now)) {
                throw expired(subject, challengeId, EmailCodeEvents.ExpiryReason.LAPSED);
            }
            return renewCode(challenge, code, now);
        });
        deliver(renewed, code, address, locale, true);
        return body(renewed, address);
    }

    /**
     * Returns when {@code proof} is the code of the challenge bound to this very request, which is then consumed
     * and committed; the gesture may run. A challenge bound to an {@code Idempotency-Key} passes again with its
     * code until the code expires, so a payment whose answer was lost can be replayed; any other passes once.
     *
     * @throws InvalidReauthCodeException  on a wrong code, counted against the code and the account
     * @throws ReauthCodeExpiredException  when the challenge is unknown, another account's, closed, lapsed, worn
     *                                     out, or bound to another request, which then replaces it, uncounted
     * @throws ReauthCodeLockedException   after ten wrong codes in 24 hours, whatever the code
     */
    public void verify(String subject, ReauthProof proof, String bindingDigest) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(proof, "proof");
        Objects.requireNonNull(bindingDigest, "bindingDigest");
        byte[] presented = secrets.codeMac(proof.challengeId(), proof.code());
        Instant now = clock.instant();
        Verification verification = transactions.execute(status ->
                verifyLocked(subject, proof.challengeId(), presented, bindingDigest, now));
        UUID challengeId = proof.challengeId();
        switch (verification.outcome()) {
            case VERIFIED, REPLAYED -> events.publishEvent(new EmailCodeEvents.Verified(subject, challengeId,
                    verification.challenge().action(), verification.outcome() == Outcome.REPLAYED));
            case INVALID -> {
                events.publishEvent(new EmailCodeEvents.Invalid(subject, challengeId,
                        verification.challenge().codeFailures(), verification.accountFailures()));
                throw InvalidReauthCodeException.wrongCode();
            }
            case SUPERSEDED -> {
                events.publishEvent(new EmailCodeEvents.Superseded(subject, challengeId,
                        EmailCodeEvents.SupersededBy.OTHER_REQUEST));
                throw new ReauthCodeExpiredException();
            }
            case EXPIRED -> throw expired(subject, challengeId, verification.expiry());
            case LOCKED -> throw locked(subject, verification.retryAfterSeconds());
        }
    }

    /** Deletes what is older than the seven days of retention; the module schedules it. */
    public int purge() {
        Instant cutoff = clock.instant().minus(EmailCodeLimits.RETENTION);
        return Objects.requireNonNull(transactions.execute(status -> store.purgeBefore(cutoff)));
    }

    private Issued issueLocked(EmailCodeIssue request, String code, Instant now) {
        String subject = request.subject();
        store.lockAccount(subject);
        refuseIfLocked(subject, now);
        EmailCodeChallenge superseded = null;
        Optional<EmailCodeChallenge> open = store.findOpen(subject);
        if (open.isPresent()) {
            EmailCodeChallenge current = open.get();
            // The same request again: a double click or the browser's back gets its own challenge back.
            if (current.openAt(now) && secrets.sameBinding(current.bindingDigest(), request.bindingDigest())) {
                if (current.codeLiveAt(now)) {
                    return new Issued(current, null, true, false);
                }
                return new Issued(renewCode(current, code, now), null, false, true);
            }
            superseded = current.superseded(now);
            store.update(superseded);
        }
        refuseOverSendingCaps(subject, now);
        UUID challengeId = UUID.randomUUID();
        EmailCodeChallenge opened = EmailCodeChallenge.open(challengeId, request,
                secrets.codeDigest(challengeId, code), now);
        store.insert(opened);
        store.record(subject, EmailCodeOccurrence.SENT, challengeId, now);
        return new Issued(opened, superseded, false, false);
    }

    private EmailCodeChallenge renewCode(EmailCodeChallenge challenge, String code, Instant now) {
        if (now.isBefore(challenge.resendAvailableAt())) {
            throw throttled(challenge.subject(), RESEND_RULE,
                    Duration.between(now, challenge.resendAvailableAt()));
        }
        refuseOverSendingCaps(challenge.subject(), now);
        EmailCodeChallenge renewed = challenge.withNewCode(secrets.codeDigest(challenge.id(), code), now);
        store.update(renewed);
        store.record(challenge.subject(), EmailCodeOccurrence.SENT, challenge.id(), now);
        return renewed;
    }

    // The code's MAC is compared whatever the outcome, so a wrong code and a dead challenge cost the same work.
    private Verification verifyLocked(String subject, UUID challengeId, byte[] presented, String bindingDigest,
                                      Instant now) {
        store.lockAccount(subject);
        Optional<EmailCodeChallenge> found = store.findForUpdate(challengeId)
                .filter(challenge -> challenge.subject().equals(subject));
        boolean matches = secrets.codeMatches(presented, found.map(EmailCodeChallenge::codeDigest).orElse(null));
        Optional<Duration> lock = lockOf(subject, now);
        if (lock.isPresent()) {
            return Verification.locked(EmailCodeLimits.retryAfterSeconds(lock.get()));
        }
        if (found.isEmpty()) {
            return Verification.expired(EmailCodeEvents.ExpiryReason.UNKNOWN);
        }
        EmailCodeChallenge challenge = found.get();
        boolean replayable = challenge.status() == EmailCodeStatus.CONSUMED && challenge.keyed();
        if (challenge.status() != EmailCodeStatus.PENDING && !replayable) {
            return Verification.expired(EmailCodeEvents.ExpiryReason.CLOSED);
        }
        if (!challenge.codeLiveAt(now)) {
            return Verification.expired(challenge.codeDigest() != null
                    && challenge.codeFailures() >= EmailCodeLimits.FAILURES_PER_CODE
                    ? EmailCodeEvents.ExpiryReason.WORN_OUT : EmailCodeEvents.ExpiryReason.LAPSED);
        }
        // Another request is no try at the code: the challenge is replaced, the counts stay.
        if (!secrets.sameBinding(challenge.bindingDigest(), bindingDigest)) {
            if (replayable) {
                return Verification.expired(EmailCodeEvents.ExpiryReason.CLOSED);
            }
            store.update(challenge.superseded(now));
            return new Verification(Outcome.SUPERSEDED, challenge, null, 0, 0);
        }
        if (!matches) {
            EmailCodeChallenge failed = challenge.withFailedCode();
            store.update(failed);
            store.record(subject, EmailCodeOccurrence.FAILED, challengeId, now);
            int accountFailures = store.window(subject, EmailCodeOccurrence.FAILED,
                    now.minus(EmailCodeLimits.FAILURE_WINDOW)).count();
            return new Verification(Outcome.INVALID, failed, null, 0, accountFailures);
        }
        if (replayable) {
            return new Verification(Outcome.REPLAYED, challenge, null, 0, 0);
        }
        if (!store.consume(challengeId, now)) {
            return Verification.expired(EmailCodeEvents.ExpiryReason.CLOSED);
        }
        return new Verification(Outcome.VERIFIED, challenge.consumed(now), null, 0, 0);
    }

    private void deliver(EmailCodeChallenge challenge, String code, String address, String locale, boolean renewed) {
        try {
            mailer.send(new EmailCodeDelivery(address, code, challenge.gesture(),
                    Duration.between(challenge.codeIssuedAt(), challenge.codeExpiresAt()), locale));
        } catch (RuntimeException failure) {
            withdraw(challenge, failure);
            events.publishEvent(new EmailCodeEvents.NotSent(challenge.subject(), challenge.action(),
                    EmailCodeEvents.NotSentReason.DELIVERY_FAILED, failure));
            throw ReauthCodeNotSentException.deliveryFailed(failure);
        }
        events.publishEvent(new EmailCodeEvents.Sent(challenge.subject(), challenge.id(), challenge.action(),
                renewed));
    }

    // Compensation: the code that did not leave is erased and its send given back; a later code is left alone.
    private void withdraw(EmailCodeChallenge sent, RuntimeException failure) {
        try {
            transactions.executeWithoutResult(status -> {
                store.lockAccount(sent.subject());
                store.findForUpdate(sent.id())
                        .filter(current -> current.status() == EmailCodeStatus.PENDING
                                && Objects.equals(current.codeDigest(), sent.codeDigest()))
                        .ifPresent(current -> store.update(current.voided(clock.instant())));
                store.forget(sent.subject(), EmailCodeOccurrence.SENT, sent.id(), sent.codeIssuedAt());
            });
        } catch (RuntimeException compensation) {
            failure.addSuppressed(compensation);
        }
    }

    private void refuseWithoutAddress(String subject, String action, String address) {
        if (!StringUtils.hasText(address)) {
            events.publishEvent(new EmailCodeEvents.NotSent(subject, action,
                    EmailCodeEvents.NotSentReason.NO_ADDRESS, null));
            throw ReauthCodeNotSentException.noAddress();
        }
    }

    private void refuseIfLocked(String subject, Instant now) {
        Optional<Duration> lock = lockOf(subject, now);
        if (lock.isPresent()) {
            throw locked(subject, EmailCodeLimits.retryAfterSeconds(lock.get()));
        }
    }

    private Optional<Duration> lockOf(String subject, Instant now) {
        return EmailCodeLimits.waitBeforeNext(store.window(subject, EmailCodeOccurrence.FAILED,
                now.minus(EmailCodeLimits.FAILURE_WINDOW)), EmailCodeLimits.FAILURES_PER_ACCOUNT,
                EmailCodeLimits.FAILURE_WINDOW, now);
    }

    private void refuseOverSendingCaps(String subject, Instant now) {
        refuseAtCap(subject, HOUR_RULE, EmailCodeLimits.CODES_PER_HOUR, EmailCodeLimits.HOUR, now);
        refuseAtCap(subject, DAY_RULE, EmailCodeLimits.CODES_PER_DAY, EmailCodeLimits.DAY, now);
    }

    private void refuseAtCap(String subject, String rule, int limit, Duration span, Instant now) {
        EmailCodeLimits.waitBeforeNext(store.window(subject, EmailCodeOccurrence.SENT, now.minus(span)), limit,
                span, now).ifPresent(wait -> {
                    throw throttled(subject, rule, wait);
                });
    }

    private RateLimitExceededException throttled(String subject, String rule, Duration wait) {
        long retryAfter = EmailCodeLimits.retryAfterSeconds(wait);
        events.publishEvent(new EmailCodeEvents.SendThrottled(subject, rule, retryAfter));
        return new RateLimitExceededException(rule, retryAfter);
    }

    private ReauthCodeLockedException locked(String subject, long retryAfterSeconds) {
        events.publishEvent(new EmailCodeEvents.Locked(subject, retryAfterSeconds));
        return new ReauthCodeLockedException(retryAfterSeconds);
    }

    private ReauthCodeExpiredException expired(String subject, UUID challengeId, EmailCodeEvents.ExpiryReason reason) {
        events.publishEvent(new EmailCodeEvents.Expired(subject, challengeId, reason));
        return new ReauthCodeExpiredException();
    }

    private static PendingConfirmation body(EmailCodeChallenge challenge, String address) {
        return PendingConfirmation.codeSentToCurrentAddress(challenge.id(), challenge.codeExpiresAt(),
                AddressMask.mask(address), challenge.resendAvailableAt());
    }

    private static TransactionOperations requiresNew(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private record Issued(EmailCodeChallenge challenge, EmailCodeChallenge superseded, boolean reused,
                          boolean renewed) {
    }

    private enum Outcome { VERIFIED, REPLAYED, INVALID, SUPERSEDED, EXPIRED, LOCKED }

    private record Verification(Outcome outcome, EmailCodeChallenge challenge, EmailCodeEvents.ExpiryReason expiry,
                                long retryAfterSeconds, int accountFailures) {

        static Verification expired(EmailCodeEvents.ExpiryReason reason) {
            return new Verification(Outcome.EXPIRED, null, reason, 0, 0);
        }

        static Verification locked(long retryAfterSeconds) {
            return new Verification(Outcome.LOCKED, null, null, retryAfterSeconds, 0);
        }
    }
}
