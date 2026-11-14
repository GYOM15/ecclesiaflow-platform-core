package com.ecclesiaflow.platform.security.reauth.code.web;

import com.ecclesiaflow.platform.contract.ApiContract;
import com.ecclesiaflow.platform.contract.ContractOperation;
import com.ecclesiaflow.platform.contract.ContractParameter;
import com.ecclesiaflow.platform.idempotency.web.IdempotencyIndex;
import com.ecclesiaflow.platform.idempotency.web.IdempotencyKeyResolver;
import com.ecclesiaflow.platform.security.reauth.PendingConfirmation;
import com.ecclesiaflow.platform.security.reauth.ReauthenticationRequiredException;
import com.ecclesiaflow.platform.security.reauth.SensitiveAction;
import com.ecclesiaflow.platform.security.reauth.code.ConfirmableGestures;
import com.ecclesiaflow.platform.security.reauth.code.EmailCodeSecrets;
import com.ecclesiaflow.platform.security.reauth.code.EmailCodeStatus;
import com.ecclesiaflow.platform.security.reauth.code.EmailCodeTestSupport;
import com.ecclesiaflow.platform.security.reauth.code.InMemoryEmailCodeStore;
import com.ecclesiaflow.platform.security.reauth.code.InvalidReauthCodeException;
import com.ecclesiaflow.platform.security.reauth.code.MutableClock;
import com.ecclesiaflow.platform.security.reauth.code.ReauthCodeExpiredException;
import com.ecclesiaflow.platform.security.reauth.code.ReauthCodeNotSentException;
import com.ecclesiaflow.platform.security.reauth.code.RecordingMailer;
import com.ecclesiaflow.platform.security.reauth.code.events.EmailCodeEvents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailCodeConfirmationTest {

    private static final Instant START = Instant.parse("2026-11-14T10:00:00Z");
    private static final String SUB = "3f2c1d4e-1111-4222-8333-944455556666";
    private static final String PURCHASE = "/billing/me/credit-packs/{packId}/purchase";

    /** The module's side, as billing writes it: the church first, then what the gesture changes. */
    static class Gestures implements ConfirmableGestures {
        final AtomicInteger prechecks = new AtomicInteger();
        RuntimeException refusal;
        List<Object> bound;

        @Override
        public Set<String> actions() {
            return Set.of("Subscription.Cancel", "CreditPacks.Purchase");
        }

        @Override
        public List<Object> boundParts(SensitiveAction action) {
            return bound != null ? bound : List.of("church-1", action.arguments());
        }

        @Override
        public void precheck(SensitiveAction action) {
            prechecks.incrementAndGet();
            if (refusal != null) {
                throw refusal;
            }
        }

        @Override
        public String emailLabel(SensitiveAction action) {
            return action.name().equals("Subscription.Cancel") ? "billing.cancel" : "billing.savedCardPayment";
        }
    }

    private final MutableClock clock = new MutableClock(START);
    private final InMemoryEmailCodeStore store = new InMemoryEmailCodeStore();
    private final RecordingMailer mailer = new RecordingMailer();
    private final EmailCodeSecrets secrets = new EmailCodeSecrets(new byte[32], new SecureRandom());
    private final List<Object> events = new ArrayList<>();
    private final Gestures gestures = new Gestures();
    private final IdempotencyKeyResolver keys = new IdempotencyKeyResolver(new IdempotencyIndex(ApiContract.of(List.of(
            new ContractOperation("POST", PURCHASE, "CreditPacks.Purchase", false,
                    Map.of(IdempotencyIndex.EXTENSION, IdempotencyIndex.REQUIRED),
                    List.of(new ContractParameter("Idempotency-Key", "header", false,
                            Map.of("type", "string", "format", "uuid"))), "billing.yaml")))));
    private final EmailCodeConfirmation confirmation = new EmailCodeConfirmation(
            EmailCodeTestSupport.service(store, secrets, mailer, events::add, clock), secrets, gestures, keys,
            events::add);
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        request("POST", "/billing/me/subscription/cancellation");
        signedIn(Map.of("email", "caleb@gmail.com", "locale", "en"));
    }

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    private void request(String method, String uri) {
        request = new MockHttpServletRequest(method, uri);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static void signedIn(Map<String, Object> claims) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject(SUB)
                .issuedAt(START).expiresAt(START.plusSeconds(300)).claims(c -> c.putAll(claims)).build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, AuthorityUtils.NO_AUTHORITIES));
    }

    private static SensitiveAction cancel() {
        return new SensitiveAction("Subscription.Cancel", SUB, List.of());
    }

    private void replay(PendingConfirmation pending, String code) {
        request("POST", "/billing/me/subscription/cancellation");
        request.addHeader("Reauth-Challenge", pending.challengeId().toString());
        request.addHeader("Reauth-Code", code);
    }

    @Nested
    @DisplayName("without the confirmation headers")
    class Issue {

        @Test
        @DisplayName("checks the gesture, then mails the code to the token's address in its locale")
        void sendsCode() {
            Optional<PendingConfirmation> pending = confirmation.confirm(cancel());

            assertThat(pending).get().satisfies(body -> {
                assertThat(body.status()).isEqualTo(PendingConfirmation.CODE_SENT);
                assertThat(body.maskedAddress()).isEqualTo("c•••@gmail.com");
            });
            assertThat(gestures.prechecks).hasValue(1);
            assertThat(mailer.sent).singleElement().satisfies(delivery -> {
                assertThat(delivery.address()).isEqualTo("caleb@gmail.com");
                assertThat(delivery.locale()).isEqualTo("en");
                assertThat(delivery.gesture()).isEqualTo("billing.cancel");
            });
            assertThat(store.only().bindingDigest()).isEqualTo(secrets.bindingDigest("Subscription.Cancel", SUB,
                    null, List.of("church-1", List.of())));
            assertThat(store.only().keyed()).isFalse();
        }

        @Test
        @DisplayName("sends nothing when the gesture's own checks refuse it, which answers its own code")
        void precheckRefuses() {
            IllegalStateException cardHeldByRecharge = new IllegalStateException("CARD_IN_USE");
            gestures.refusal = cardHeldByRecharge;

            assertThatThrownBy(() -> confirmation.confirm(cancel())).isSameAs(cardHeldByRecharge);
            assertThat(mailer.sent).isEmpty();
            assertThat(store.challenges).isEmpty();
        }

        @Test
        @DisplayName("answers 503 without an address in the token")
        void noAddress() {
            signedIn(Map.of("locale", "fr"));

            assertThatThrownBy(() -> confirmation.confirm(cancel())).isInstanceOf(ReauthCodeNotSentException.class);
            assertThat(mailer.sent).isEmpty();
        }

        @Test
        @DisplayName("binds the code to the Idempotency-Key of an operation that runs the primitive")
        void keyed() {
            request("POST", "/billing/me/credit-packs/pack-1/purchase");
            UUID key = UUID.randomUUID();
            request.addHeader("Idempotency-Key", key.toString());
            SensitiveAction purchase = new SensitiveAction("CreditPacks.Purchase", SUB, List.of("pack-1"));

            confirmation.confirm(purchase);

            assertThat(store.only().keyed()).isTrue();
            assertThat(store.only().bindingDigest()).isEqualTo(secrets.bindingDigest("CreditPacks.Purchase", SUB,
                    key, List.of("church-1", List.of("pack-1"))));
            assertThat(mailer.sent).singleElement().extracting(d -> d.gesture())
                    .isEqualTo("billing.savedCardPayment");
        }
    }

    @Nested
    @DisplayName("with the confirmation headers")
    class Verify {

        @Test
        @DisplayName("lets the gesture run on its code, without checking or mailing again")
        void verified() {
            PendingConfirmation pending = confirmation.confirm(cancel()).orElseThrow();
            replay(pending, mailer.lastCode());

            assertThat(confirmation.confirm(cancel())).isEmpty();
            assertThat(store.get(pending.challengeId()).status()).isEqualTo(EmailCodeStatus.CONSUMED);
            assertThat(gestures.prechecks).hasValue(1);
            assertThat(mailer.sent).hasSize(1);
        }

        @Test
        @DisplayName("refuses the code for another request of the same gesture")
        void otherRequest() {
            PendingConfirmation pending = confirmation.confirm(cancel()).orElseThrow();
            replay(pending, mailer.lastCode());
            gestures.bound = List.of("church-2");

            assertThatThrownBy(() -> confirmation.confirm(cancel())).isInstanceOf(ReauthCodeExpiredException.class);
        }

        @Test
        @DisplayName("refuses the code replayed under another Idempotency-Key")
        void otherKey() {
            request("POST", "/billing/me/credit-packs/pack-1/purchase");
            request.addHeader("Idempotency-Key", UUID.randomUUID().toString());
            SensitiveAction purchase = new SensitiveAction("CreditPacks.Purchase", SUB, List.of("pack-1"));
            PendingConfirmation pending = confirmation.confirm(purchase).orElseThrow();
            request("POST", "/billing/me/credit-packs/pack-1/purchase");
            request.addHeader("Idempotency-Key", UUID.randomUUID().toString());
            request.addHeader("Reauth-Challenge", pending.challengeId().toString());
            request.addHeader("Reauth-Code", mailer.lastCode());

            assertThatThrownBy(() -> confirmation.confirm(purchase)).isInstanceOf(ReauthCodeExpiredException.class);
        }

        @Test
        @DisplayName("refuses malformed headers as not a try, and says so")
        void malformed() {
            request.addHeader("Reauth-Challenge", "not-a-uuid");
            request.addHeader("Reauth-Code", "12");

            assertThatThrownBy(() -> confirmation.confirm(cancel()))
                    .isInstanceOfSatisfying(InvalidReauthCodeException.class,
                            refused -> assertThat(refused.isCounted()).isFalse());
            assertThat(events).containsExactly(new EmailCodeEvents.Malformed(SUB, "Subscription.Cancel"));
            assertThat(gestures.prechecks).hasValue(0);
        }
    }

    @Nested
    @DisplayName("resending")
    class Resend {

        @Test
        @DisplayName("mails a new code for the caller's challenge, to the token's address")
        void resend() {
            PendingConfirmation pending = confirmation.confirm(cancel()).orElseThrow();
            clock.advance(Duration.ofSeconds(60));

            PendingConfirmation resent = confirmation.resend(pending.challengeId());

            assertThat(resent.challengeId()).isEqualTo(pending.challengeId());
            assertThat(mailer.sent).hasSize(2);
        }

        @Test
        @DisplayName("needs a token that names an account")
        void noToken() {
            SecurityContextHolder.clearContext();

            assertThatThrownBy(() -> confirmation.resend(UUID.randomUUID()))
                    .isInstanceOf(ReauthenticationRequiredException.class);
        }
    }

    @Test
    @DisplayName("refuses a gesture the module does not confirm by code: a bug, never a pass")
    void unknownGesture() {
        assertThatThrownBy(() -> confirmation.confirm(new SensitiveAction("Members.DeleteMyAccount", SUB, List.of())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Members.DeleteMyAccount");
    }

    @Test
    @DisplayName("refuses a module whose bound parts are missing, rather than bind the code to nothing")
    void noBoundParts() {
        EmailCodeConfirmation broken = new EmailCodeConfirmation(
                EmailCodeTestSupport.service(store, secrets, mailer, events::add, clock), secrets,
                new Gestures() {
                    @Override
                    public List<Object> boundParts(SensitiveAction action) {
                        return null;
                    }
                }, null, events::add);

        assertThatThrownBy(() -> broken.confirm(cancel())).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("boundParts");
    }

    @Test
    @DisplayName("needs the request it serves")
    void outsideRequest() {
        RequestContextHolder.resetRequestAttributes();

        assertThatThrownBy(() -> confirmation.confirm(cancel())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("names the module's gestures")
    void actions() {
        assertThat(confirmation.actions()).containsExactlyInAnyOrder("Subscription.Cancel", "CreditPacks.Purchase");
        assertThatCode(() -> confirmation.actions().size()).doesNotThrowAnyException();
    }
}
