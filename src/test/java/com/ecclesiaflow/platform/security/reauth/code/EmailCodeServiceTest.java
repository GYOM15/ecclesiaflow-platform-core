package com.ecclesiaflow.platform.security.reauth.code;

import com.ecclesiaflow.platform.ratelimit.RateLimitExceededException;
import com.ecclesiaflow.platform.security.reauth.PendingConfirmation;
import com.ecclesiaflow.platform.security.reauth.code.events.EmailCodeEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** One test per duration, cap and state of PLAN-REAUTH-CODE §4.4 to §4.6. */
class EmailCodeServiceTest {

    private static final Instant START = Instant.parse("2026-11-14T10:00:00Z");
    private static final String SUB = "3f2c1d4e-1111-4222-8333-944455556666";
    private static final String OTHER_SUB = "9a8b7c6d-1111-4222-8333-944455556666";
    private static final String ADDRESS = "caleb@gmail.com";

    /** Records each mail, and refuses them while {@link #failure} is set. */
    static final class RecordingMailer implements EmailCodeMailer {
        final List<EmailCodeDelivery> sent = new ArrayList<>();
        RuntimeException failure;

        @Override
        public void send(EmailCodeDelivery delivery) {
            if (failure != null) {
                throw failure;
            }
            sent.add(delivery);
        }

        String lastCode() {
            return sent.get(sent.size() - 1).code();
        }
    }

    private final MutableClock clock = new MutableClock(START);
    private final InMemoryEmailCodeStore store = new InMemoryEmailCodeStore();
    private final RecordingMailer mailer = new RecordingMailer();
    private final EmailCodeSecrets secrets = new EmailCodeSecrets(new byte[32], new SecureRandom());
    private final List<Object> events = new ArrayList<>();
    private EmailCodeService service;

    @BeforeEach
    void setUp() {
        service = new EmailCodeService(store, secrets, mailer, store.transactions, events::add, clock);
    }

    private String binding(Object... gesture) {
        return secrets.bindingDigest(gesture);
    }

    private EmailCodeIssue issueFor(String subject, String bindingDigest, boolean keyed) {
        return new EmailCodeIssue(subject, "Subscription.Cancel", "billing.cancel", bindingDigest, keyed, ADDRESS,
                "fr");
    }

    private PendingConfirmation issue(String bindingDigest) {
        return service.issue(issueFor(SUB, bindingDigest, false));
    }

    private void verify(PendingConfirmation pending, String code, String bindingDigest) {
        service.verify(SUB, new ReauthProof(pending.challengeId(), code), bindingDigest);
    }

    private static String wrong(String code) {
        return code.equals("000000") ? "000001" : "000000";
    }

    private <T> List<T> eventsOf(Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }

    @Nested
    @DisplayName("issuing a code")
    class Issue {

        @Test
        @DisplayName("mails six digits naming the gesture, and answers what the dialog needs")
        void firstCode() {
            PendingConfirmation pending = issue(binding("cancel"));

            assertThat(mailer.sent).singleElement().satisfies(delivery -> {
                assertThat(delivery.address()).isEqualTo(ADDRESS);
                assertThat(delivery.code()).matches("[0-9]{6}");
                assertThat(delivery.gesture()).isEqualTo("billing.cancel");
                assertThat(delivery.lifetime()).isEqualTo(Duration.ofMinutes(10));
                assertThat(delivery.locale()).isEqualTo("fr");
            });
            assertThat(pending).isEqualTo(PendingConfirmation.codeSentToCurrentAddress(pending.challengeId(),
                    START.plus(Duration.ofMinutes(10)), "c•••@gmail.com", START.plusSeconds(60)));
            assertThat(eventsOf(EmailCodeEvents.Sent.class)).containsExactly(
                    new EmailCodeEvents.Sent(SUB, pending.challengeId(), "Subscription.Cancel", false));
        }

        @Test
        @DisplayName("keeps only the code's keyed digest, bound to its challenge, never the code or the address")
        void storesDigestsOnly() {
            PendingConfirmation pending = issue(binding("cancel"));
            EmailCodeChallenge stored = store.only();

            assertThat(stored.codeDigest()).isEqualTo(secrets.codeDigest(pending.challengeId(), mailer.lastCode()))
                    .isNotEqualTo(secrets.codeDigest(UUID.randomUUID(), mailer.lastCode()))
                    .doesNotContain(mailer.lastCode());
            assertThat(stored.toString()).doesNotContain(stored.codeDigest(), stored.bindingDigest(), SUB, ADDRESS);
            assertThat(stored.status()).isEqualTo(EmailCodeStatus.PENDING);
            assertThat(stored.expiresAt()).isEqualTo(START.plus(Duration.ofMinutes(30)));
            assertThat(store.count(SUB, EmailCodeOccurrence.SENT)).isEqualTo(1);
            assertThat(store.locks).containsOnly(SUB);
        }

        @Test
        @DisplayName("answers the same 202 to the same request while its code holds, without mailing again")
        void sameRequestReused() {
            PendingConfirmation first = issue(binding("cancel"));
            clock.advance(Duration.ofMinutes(9));

            PendingConfirmation again = issue(binding("cancel"));

            assertThat(again).isEqualTo(first);
            assertThat(mailer.sent).hasSize(1);
            assertThat(store.count(SUB, EmailCodeOccurrence.SENT)).isEqualTo(1);
            assertThat(eventsOf(EmailCodeEvents.Reused.class)).hasSize(1);
        }

        @Test
        @DisplayName("mails a new code to the same request once its code expired, as a resend would")
        void sameRequestLapsedCode() {
            PendingConfirmation first = issue(binding("cancel"));
            String firstCode = mailer.lastCode();
            clock.advance(Duration.ofMinutes(10));

            PendingConfirmation renewed = issue(binding("cancel"));

            assertThat(renewed.challengeId()).isEqualTo(first.challengeId());
            assertThat(renewed.expiresAt()).isEqualTo(START.plus(Duration.ofMinutes(20)));
            assertThat(mailer.sent).hasSize(2);
            assertThat(eventsOf(EmailCodeEvents.Sent.class)).extracting(EmailCodeEvents.Sent::renewed)
                    .containsExactly(false, true);
            if (!mailer.lastCode().equals(firstCode)) {
                assertThatThrownBy(() -> verify(renewed, firstCode, binding("cancel")))
                        .isInstanceOf(InvalidReauthCodeException.class);
            }
        }

        @Test
        @DisplayName("holds the same request to the 60 s of a resend when its code wore out")
        void sameRequestWornOutCode() {
            PendingConfirmation pending = issue(binding("cancel"));
            String code = mailer.lastCode();
            for (int i = 0; i < 5; i++) {
                assertThatThrownBy(() -> verify(pending, wrong(code), binding("cancel")))
                        .isInstanceOf(InvalidReauthCodeException.class);
            }

            RateLimitExceededException throttled = catchThrowableOfType(RateLimitExceededException.class,
                    () -> issue(binding("cancel")));

            assertThat(throttled.getRule()).isEqualTo(EmailCodeService.RESEND_RULE);
            assertThat(throttled.getRetryAfterSeconds()).isEqualTo(60);
            clock.advance(Duration.ofSeconds(60));
            assertThat(issue(binding("cancel")).challengeId()).isEqualTo(pending.challengeId());
            assertThat(mailer.sent).hasSize(2);
        }

        @Test
        @DisplayName("replaces the open challenge with another gesture's, whose code then counts for nothing")
        void otherGestureSupersedes() {
            PendingConfirmation cancel = issue(binding("cancel"));
            String cancelCode = mailer.lastCode();

            PendingConfirmation removal = issue(binding("card-removal"));

            assertThat(removal.challengeId()).isNotEqualTo(cancel.challengeId());
            assertThat(store.get(cancel.challengeId()).status()).isEqualTo(EmailCodeStatus.SUPERSEDED);
            assertThat(store.get(cancel.challengeId()).closedAt()).isEqualTo(START);
            assertThat(eventsOf(EmailCodeEvents.Superseded.class)).containsExactly(new EmailCodeEvents.Superseded(
                    SUB, cancel.challengeId(), EmailCodeEvents.SupersededBy.NEW_GESTURE));
            assertThatThrownBy(() -> verify(cancel, cancelCode, binding("cancel")))
                    .isInstanceOf(ReauthCodeExpiredException.class);
        }

        @Test
        @DisplayName("replaces a lapsed challenge even for the same request: past 30 minutes the gesture starts again")
        void lapsedChallenge() {
            PendingConfirmation first = issue(binding("cancel"));
            clock.advance(Duration.ofMinutes(30));

            PendingConfirmation next = issue(binding("cancel"));

            assertThat(next.challengeId()).isNotEqualTo(first.challengeId());
            assertThat(store.get(first.challengeId()).status()).isEqualTo(EmailCodeStatus.SUPERSEDED);
        }

        @Test
        @DisplayName("mails three codes an hour, resends included, then answers 429 until the first leaves the hour")
        void threePerHour() {
            PendingConfirmation pending = issue(binding("cancel"));
            clock.advance(Duration.ofSeconds(60));
            service.resend(SUB, pending.challengeId(), ADDRESS, "fr");
            clock.advance(Duration.ofSeconds(60));
            issue(binding("plan-change"));
            clock.advance(Duration.ofSeconds(60));

            RateLimitExceededException throttled = catchThrowableOfType(RateLimitExceededException.class,
                    () -> issue(binding("card-removal")));

            assertThat(throttled.getRule()).isEqualTo(EmailCodeService.HOUR_RULE);
            assertThat(throttled.getRetryAfterSeconds()).isEqualTo(Duration.ofMinutes(57).toSeconds());
            assertThat(mailer.sent).hasSize(3);
            assertThat(eventsOf(EmailCodeEvents.SendThrottled.class)).hasSize(1);
            clock.advance(Duration.ofMinutes(57));
            assertThatCode(() -> issue(binding("card-removal"))).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("mails ten codes a day at most, whatever the hours")
        void tenPerDay() {
            for (int i = 0; i < 10; i++) {
                issue(binding("gesture", i));
                clock.advance(Duration.ofMinutes(21));
            }

            RateLimitExceededException throttled = catchThrowableOfType(RateLimitExceededException.class,
                    () -> issue(binding("gesture", 10)));

            assertThat(throttled.getRule()).isEqualTo(EmailCodeService.DAY_RULE);
            assertThat(throttled.getRetryAfterSeconds())
                    .isEqualTo(Duration.ofDays(1).minus(Duration.ofMinutes(210)).toSeconds());
            assertThat(mailer.sent).hasSize(10);
        }

        @Test
        @DisplayName("keeps the open challenge when the caps refuse the next gesture: nothing of the refusal stays")
        void refusedSupersedeRolledBack() {
            issue(binding("one"));
            clock.advance(Duration.ofMinutes(1));
            issue(binding("two"));
            clock.advance(Duration.ofMinutes(1));
            PendingConfirmation third = issue(binding("three"));
            String code = mailer.lastCode();

            assertThatThrownBy(() -> issue(binding("four"))).isInstanceOf(RateLimitExceededException.class);

            assertThat(store.get(third.challengeId()).status()).isEqualTo(EmailCodeStatus.PENDING);
            assertThatCode(() -> verify(third, code, binding("three"))).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("sends nothing to a locked account and says until when")
        void locked() {
            lockAccount();
            clock.advance(Duration.ofHours(2));
            mailer.sent.clear();

            ReauthCodeLockedException refused = catchThrowableOfType(ReauthCodeLockedException.class,
                    () -> issue(binding("other")));

            assertThat(refused.getRetryAfterSeconds()).isEqualTo(Duration.ofHours(22).toSeconds());
            assertThat(mailer.sent).isEmpty();
        }

        @Test
        @DisplayName("without an address in the token sends and stores nothing, and answers 503")
        void noAddress() {
            EmailCodeIssue anonymous = new EmailCodeIssue(SUB, "Subscription.Cancel", "billing.cancel",
                    binding("cancel"), false, " ", null);

            assertThatThrownBy(() -> service.issue(anonymous)).isInstanceOf(ReauthCodeNotSentException.class)
                    .hasNoCause();
            assertThat(store.challenges).isEmpty();
            assertThat(store.transactionsRun).isZero();
            assertThat(events).containsExactly(new EmailCodeEvents.NotSent(SUB, "Subscription.Cancel",
                    EmailCodeEvents.NotSentReason.NO_ADDRESS, null));
        }

        @Test
        @DisplayName("voids the challenge a refused mail leaves, counts no send, and answers 503")
        void mailRefused() {
            IllegalStateException down = new IllegalStateException("communication unreachable");
            mailer.failure = down;

            assertThatThrownBy(() -> issue(binding("cancel"))).isInstanceOf(ReauthCodeNotSentException.class)
                    .hasCause(down);

            EmailCodeChallenge voided = store.only();
            assertThat(voided.status()).isEqualTo(EmailCodeStatus.VOID);
            assertThat(voided.codeDigest()).isNull();
            assertThat(store.count(SUB, EmailCodeOccurrence.SENT)).isZero();
            assertThat(eventsOf(EmailCodeEvents.NotSent.class)).containsExactly(new EmailCodeEvents.NotSent(SUB,
                    "Subscription.Cancel", EmailCodeEvents.NotSentReason.DELIVERY_FAILED, down));
            mailer.failure = null;
            for (int i = 0; i < 3; i++) {
                issue(binding("gesture", i));
            }
            assertThat(mailer.sent).hasSize(3);
        }

        @Test
        @DisplayName("keeps the mail's failure when voiding fails too, the second attached to it")
        void compensationFails() {
            IllegalStateException down = new IllegalStateException("communication unreachable");
            mailer.failure = down;
            EmailCodeService failingCompensation = new EmailCodeService(store, secrets, mailer,
                    new org.springframework.transaction.support.TransactionOperations() {
                        private int calls;

                        @Override
                        public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                            if (++calls == 2) {
                                throw new IllegalStateException("database gone");
                            }
                            return store.transactions.execute(action);
                        }
                    }, events::add, clock);

            Throwable thrown = catchThrowableOfType(ReauthCodeNotSentException.class,
                    () -> failingCompensation.issue(issueFor(SUB, binding("cancel"), false)));

            assertThat(thrown.getCause()).isSameAs(down);
            assertThat(down.getSuppressed()).singleElement().extracting(Throwable::getMessage).isEqualTo("database gone");
        }

        @Test
        @DisplayName("builds its transactions as new ones on the module's manager")
        void requiresNewTransactions() {
            List<Integer> propagations = new ArrayList<>();
            AbstractPlatformTransactionManager manager = new AbstractPlatformTransactionManager() {
                @Override
                protected Object doGetTransaction() {
                    return new Object();
                }

                @Override
                protected void doBegin(Object transaction,
                                       org.springframework.transaction.TransactionDefinition definition) {
                    propagations.add(definition.getPropagationBehavior());
                }

                @Override
                protected void doCommit(DefaultTransactionStatus status) {
                }

                @Override
                protected void doRollback(DefaultTransactionStatus status) {
                }
            };
            EmailCodeService managed = new EmailCodeService(store, secrets, mailer, manager, events::add, clock);

            managed.issue(issueFor(SUB, binding("cancel"), false));

            assertThat(propagations).containsExactly(
                    org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }
    }

    @Nested
    @DisplayName("checking a code")
    class Verify {

        @Test
        @DisplayName("passes the right code once and consumes it before the gesture; a second use answers 410")
        void rightCodeOnce() {
            PendingConfirmation pending = issue(binding("cancel"));
            String code = mailer.lastCode();

            verify(pending, code, binding("cancel"));

            assertThat(store.get(pending.challengeId()).status()).isEqualTo(EmailCodeStatus.CONSUMED);
            assertThat(store.get(pending.challengeId()).closedAt()).isEqualTo(START);
            assertThat(eventsOf(EmailCodeEvents.Verified.class)).containsExactly(new EmailCodeEvents.Verified(SUB,
                    pending.challengeId(), "Subscription.Cancel", false));
            assertThatThrownBy(() -> verify(pending, code, binding("cancel")))
                    .isInstanceOf(ReauthCodeExpiredException.class);
            assertThat(eventsOf(EmailCodeEvents.Expired.class)).extracting(EmailCodeEvents.Expired::reason)
                    .containsExactly(EmailCodeEvents.ExpiryReason.CLOSED);
        }

        @Test
        @DisplayName("passes the same keyed request again with the same code until the code expires")
        void keyedReplay() {
            PendingConfirmation pending = service.issue(issueFor(SUB, binding("pay", "key"), true));
            String code = mailer.lastCode();

            verify(pending, code, binding("pay", "key"));
            clock.advance(Duration.ofMinutes(9));
            verify(pending, code, binding("pay", "key"));

            assertThat(eventsOf(EmailCodeEvents.Verified.class)).extracting(EmailCodeEvents.Verified::replayed)
                    .containsExactly(false, true);
            assertThatThrownBy(() -> verify(pending, code, binding("pay", "other-key")))
                    .isInstanceOf(ReauthCodeExpiredException.class);
            assertThatThrownBy(() -> verify(pending, wrong(code), binding("pay", "key")))
                    .isInstanceOf(InvalidReauthCodeException.class);
            assertThat(store.count(SUB, EmailCodeOccurrence.FAILED)).isEqualTo(1);
            clock.advance(Duration.ofMinutes(1));
            assertThatThrownBy(() -> verify(pending, code, binding("pay", "key")))
                    .isInstanceOf(ReauthCodeExpiredException.class);
            assertThat(store.get(pending.challengeId()).status()).isEqualTo(EmailCodeStatus.CONSUMED);
        }

        @Test
        @DisplayName("refuses a wrong code 400, counted on the code and on the account, the refusal committed")
        void wrongCode() {
            PendingConfirmation pending = issue(binding("cancel"));
            String code = mailer.lastCode();

            InvalidReauthCodeException refused = catchThrowableOfType(InvalidReauthCodeException.class,
                    () -> verify(pending, wrong(code), binding("cancel")));

            assertThat(refused.isCounted()).isTrue();
            assertThat(store.get(pending.challengeId()).codeFailures()).isEqualTo(1);
            assertThat(store.get(pending.challengeId()).status()).isEqualTo(EmailCodeStatus.PENDING);
            assertThat(eventsOf(EmailCodeEvents.Invalid.class)).containsExactly(
                    new EmailCodeEvents.Invalid(SUB, pending.challengeId(), 1, 1));
            assertThatCode(() -> verify(pending, code, binding("cancel"))).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("kills the code at its fifth wrong try: even the right one then answers 410")
        void fifthFailureKillsCode() {
            PendingConfirmation pending = issue(binding("cancel"));
            String code = mailer.lastCode();
            for (int i = 0; i < 5; i++) {
                assertThatThrownBy(() -> verify(pending, wrong(code), binding("cancel")))
                        .isInstanceOf(InvalidReauthCodeException.class);
            }

            assertThatThrownBy(() -> verify(pending, code, binding("cancel")))
                    .isInstanceOf(ReauthCodeExpiredException.class);
            assertThat(eventsOf(EmailCodeEvents.Expired.class)).extracting(EmailCodeEvents.Expired::reason)
                    .containsExactly(EmailCodeEvents.ExpiryReason.WORN_OUT);
            assertThat(store.count(SUB, EmailCodeOccurrence.FAILED)).isEqualTo(5);
        }

        @Test
        @DisplayName("locks the account at ten wrong codes in 24 h, despite resends, new gestures and a success")
        void tenthFailureLocksAccount() {
            PendingConfirmation first = issue(binding("cancel"));
            String firstCode = mailer.lastCode();
            for (int i = 0; i < 4; i++) {
                assertThatThrownBy(() -> verify(first, wrong(firstCode), binding("cancel")))
                        .isInstanceOf(InvalidReauthCodeException.class);
            }
            verify(first, firstCode, binding("cancel"));
            clock.advance(Duration.ofMinutes(5));
            PendingConfirmation second = issue(binding("card-removal"));
            String secondCode = mailer.lastCode();
            for (int i = 0; i < 5; i++) {
                assertThatThrownBy(() -> verify(second, wrong(secondCode), binding("card-removal")))
                        .isInstanceOf(InvalidReauthCodeException.class);
            }
            clock.advance(Duration.ofSeconds(60));
            PendingConfirmation resent = service.resend(SUB, second.challengeId(), ADDRESS, "fr");
            String resentCode = mailer.lastCode();
            assertThatThrownBy(() -> verify(resent, wrong(resentCode), binding("card-removal")))
                    .isInstanceOf(InvalidReauthCodeException.class);

            ReauthCodeLockedException locked = catchThrowableOfType(ReauthCodeLockedException.class,
                    () -> verify(resent, resentCode, binding("card-removal")));

            assertThat(locked.getRetryAfterSeconds()).isEqualTo(Duration.ofDays(1).minusMinutes(6).toSeconds());
            assertThat(store.count(SUB, EmailCodeOccurrence.FAILED)).isEqualTo(10);
            assertThat(eventsOf(EmailCodeEvents.Locked.class)).hasSize(1);
            assertThat(store.get(second.challengeId()).status()).isEqualTo(EmailCodeStatus.PENDING);
            assertThatThrownBy(() -> service.resend(SUB, second.challengeId(), ADDRESS, "fr"))
                    .isInstanceOf(ReauthCodeLockedException.class);
        }

        @Test
        @DisplayName("lifts the lock once the oldest failure leaves the 24 h, and not before")
        void lockLifts() {
            lockAccount();
            clock.advance(Duration.ofHours(24).minusSeconds(1));
            assertThatThrownBy(() -> issue(binding("other"))).isInstanceOf(ReauthCodeLockedException.class);

            clock.advance(Duration.ofSeconds(1));

            assertThatCode(() -> issue(binding("other"))).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("answers 410 for an unknown challenge, and for another account's without touching it")
        void unknownOrForeign() {
            PendingConfirmation theirs = service.issue(issueFor(OTHER_SUB, binding("cancel"), false));
            String theirCode = mailer.lastCode();

            assertThatThrownBy(() -> service.verify(SUB, new ReauthProof(UUID.randomUUID(), "123456"),
                    binding("cancel"))).isInstanceOf(ReauthCodeExpiredException.class);
            assertThatThrownBy(() -> service.verify(SUB, new ReauthProof(theirs.challengeId(), theirCode),
                    binding("cancel"))).isInstanceOf(ReauthCodeExpiredException.class);

            assertThat(store.get(theirs.challengeId()).status()).isEqualTo(EmailCodeStatus.PENDING);
            assertThat(store.get(theirs.challengeId()).codeFailures()).isZero();
            assertThat(store.count(SUB, EmailCodeOccurrence.FAILED)).isZero();
            assertThat(eventsOf(EmailCodeEvents.Expired.class)).extracting(EmailCodeEvents.Expired::reason)
                    .containsExactly(EmailCodeEvents.ExpiryReason.UNKNOWN, EmailCodeEvents.ExpiryReason.UNKNOWN);
        }

        @Test
        @DisplayName("replaces the challenge presented with another request, uncounted, and answers 410")
        void otherRequest() {
            PendingConfirmation pending = issue(binding("plan", "STARTER"));
            String code = mailer.lastCode();

            assertThatThrownBy(() -> verify(pending, code, binding("plan", "PREMIUM")))
                    .isInstanceOf(ReauthCodeExpiredException.class);

            assertThat(store.get(pending.challengeId()).status()).isEqualTo(EmailCodeStatus.SUPERSEDED);
            assertThat(store.count(SUB, EmailCodeOccurrence.FAILED)).isZero();
            assertThat(eventsOf(EmailCodeEvents.Superseded.class)).containsExactly(new EmailCodeEvents.Superseded(
                    SUB, pending.challengeId(), EmailCodeEvents.SupersededBy.OTHER_REQUEST));
            assertThatThrownBy(() -> verify(pending, code, binding("plan", "STARTER")))
                    .isInstanceOf(ReauthCodeExpiredException.class);
        }

        @Test
        @DisplayName("answers 410 once the code's ten minutes are over")
        void lapsedCode() {
            PendingConfirmation pending = issue(binding("cancel"));
            String code = mailer.lastCode();
            clock.advance(Duration.ofMinutes(10));

            assertThatThrownBy(() -> verify(pending, code, binding("cancel")))
                    .isInstanceOf(ReauthCodeExpiredException.class);
            assertThat(eventsOf(EmailCodeEvents.Expired.class)).extracting(EmailCodeEvents.Expired::reason)
                    .containsExactly(EmailCodeEvents.ExpiryReason.LAPSED);
        }

        @Test
        @DisplayName("answers 410 for a voided challenge, whatever was presented")
        void voided() {
            mailer.failure = new IllegalStateException("down");
            assertThatThrownBy(() -> issue(binding("cancel"))).isInstanceOf(ReauthCodeNotSentException.class);
            EmailCodeChallenge voided = store.only();

            assertThatThrownBy(() -> service.verify(SUB, new ReauthProof(voided.id(), "000000"), binding("cancel")))
                    .isInstanceOf(ReauthCodeExpiredException.class);
            assertThat(store.count(SUB, EmailCodeOccurrence.FAILED)).isZero();
        }
    }

    @Nested
    @DisplayName("resending a code")
    class Resend {

        @Test
        @DisplayName("mails a new code on the same challenge; the old one dies and the new has its own five tries")
        void newCode() {
            PendingConfirmation pending = issue(binding("cancel"));
            String oldCode = mailer.lastCode();
            assertThatThrownBy(() -> verify(pending, wrong(oldCode), binding("cancel")))
                    .isInstanceOf(InvalidReauthCodeException.class);
            clock.advance(Duration.ofSeconds(60));

            PendingConfirmation resent = service.resend(SUB, pending.challengeId(), ADDRESS, "en");

            assertThat(resent.challengeId()).isEqualTo(pending.challengeId());
            assertThat(resent.expiresAt()).isEqualTo(START.plusSeconds(60).plus(Duration.ofMinutes(10)));
            assertThat(resent.resendAvailableAt()).isEqualTo(START.plusSeconds(120));
            assertThat(store.get(pending.challengeId()).codeFailures()).isZero();
            assertThat(store.count(SUB, EmailCodeOccurrence.FAILED)).isEqualTo(1);
            assertThat(mailer.sent.get(1).locale()).isEqualTo("en");
            String newCode = mailer.lastCode();
            if (!newCode.equals(oldCode)) {
                assertThatThrownBy(() -> verify(pending, oldCode, binding("cancel")))
                        .isInstanceOf(InvalidReauthCodeException.class);
            }
            assertThatCode(() -> verify(pending, newCode, binding("cancel"))).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("waits 60 s after the last code, and says how long is left")
        void resendDelay() {
            PendingConfirmation pending = issue(binding("cancel"));
            clock.advance(Duration.ofSeconds(45));

            RateLimitExceededException throttled = catchThrowableOfType(RateLimitExceededException.class,
                    () -> service.resend(SUB, pending.challengeId(), ADDRESS, "fr"));

            assertThat(throttled.getRule()).isEqualTo(EmailCodeService.RESEND_RULE);
            assertThat(throttled.getRetryAfterSeconds()).isEqualTo(15);
            assertThat(mailer.sent).hasSize(1);
        }

        @Test
        @DisplayName("never lets a code outlive its challenge's thirty minutes")
        void codeCappedByChallenge() {
            PendingConfirmation pending = issue(binding("cancel"));
            clock.advance(Duration.ofMinutes(25));

            PendingConfirmation resent = service.resend(SUB, pending.challengeId(), ADDRESS, "fr");

            assertThat(resent.expiresAt()).isEqualTo(START.plus(Duration.ofMinutes(30)));
            assertThat(mailer.sent.get(1).lifetime()).isEqualTo(Duration.ofMinutes(5));
        }

        @Test
        @DisplayName("answers 410 for an unknown, foreign, consumed or lapsed challenge")
        void deadChallenges() {
            PendingConfirmation theirs = service.issue(issueFor(OTHER_SUB, binding("cancel"), false));
            PendingConfirmation mine = issue(binding("cancel"));
            verify(mine, mailer.lastCode(), binding("cancel"));
            PendingConfirmation lapsed = issue(binding("removal"));
            clock.advance(Duration.ofMinutes(30));

            for (UUID challengeId : List.of(UUID.randomUUID(), theirs.challengeId(), mine.challengeId(),
                    lapsed.challengeId())) {
                assertThatThrownBy(() -> service.resend(SUB, challengeId, ADDRESS, "fr"))
                        .isInstanceOf(ReauthCodeExpiredException.class);
            }
            assertThat(eventsOf(EmailCodeEvents.Expired.class)).extracting(EmailCodeEvents.Expired::reason)
                    .containsExactly(EmailCodeEvents.ExpiryReason.UNKNOWN, EmailCodeEvents.ExpiryReason.UNKNOWN,
                            EmailCodeEvents.ExpiryReason.CLOSED, EmailCodeEvents.ExpiryReason.LAPSED);
        }

        @Test
        @DisplayName("voids the challenge when the new mail is refused: the old code is dead too, 503")
        void mailRefused() {
            PendingConfirmation pending = issue(binding("cancel"));
            String oldCode = mailer.lastCode();
            clock.advance(Duration.ofSeconds(60));
            mailer.failure = new IllegalStateException("down");

            assertThatThrownBy(() -> service.resend(SUB, pending.challengeId(), ADDRESS, "fr"))
                    .isInstanceOf(ReauthCodeNotSentException.class);

            assertThat(store.get(pending.challengeId()).status()).isEqualTo(EmailCodeStatus.VOID);
            assertThat(store.count(SUB, EmailCodeOccurrence.SENT)).isEqualTo(1);
            assertThatThrownBy(() -> verify(pending, oldCode, binding("cancel")))
                    .isInstanceOf(ReauthCodeExpiredException.class);
        }

        @Test
        @DisplayName("without an address answers 503 and changes nothing")
        void noAddress() {
            PendingConfirmation pending = issue(binding("cancel"));
            clock.advance(Duration.ofSeconds(60));

            assertThatThrownBy(() -> service.resend(SUB, pending.challengeId(), null, "fr"))
                    .isInstanceOf(ReauthCodeNotSentException.class);
            assertThat(mailer.sent).hasSize(1);
        }
    }

    @Test
    @DisplayName("purges what is older than seven days, counts included")
    void purge() {
        issue(binding("old"));
        clock.advance(Duration.ofDays(7).plusSeconds(1));
        issue(binding("recent"));

        assertThat(service.purge()).isEqualTo(2);
        assertThat(store.challenges).hasSize(1);
        assertThat(store.count(SUB, EmailCodeOccurrence.SENT)).isEqualTo(1);
    }

    /** Ten wrong codes now, over two codes of one challenge. */
    private void lockAccount() {
        PendingConfirmation pending = issue(binding("lock"));
        for (int round = 0; round < 2; round++) {
            String code = mailer.lastCode();
            for (int i = 0; i < 5; i++) {
                assertThatThrownBy(() -> verify(pending, wrong(code), binding("lock")))
                        .isInstanceOf(InvalidReauthCodeException.class);
            }
            if (round == 0) {
                store.challenges.put(pending.challengeId(), store.get(pending.challengeId())
                        .withNewCode(secrets.codeDigest(pending.challengeId(), "424242"), clock.instant()));
                mailer.sent.add(new EmailCodeDelivery(ADDRESS, "424242", "billing.cancel",
                        Duration.ofMinutes(10), "fr"));
            }
        }
        assertThat(store.count(SUB, EmailCodeOccurrence.FAILED)).isEqualTo(10);
    }
}
