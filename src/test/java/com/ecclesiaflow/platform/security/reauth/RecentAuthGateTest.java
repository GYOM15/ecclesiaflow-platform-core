package com.ecclesiaflow.platform.security.reauth;

import com.ecclesiaflow.platform.security.reauth.code.ConfirmationCodeSentException;
import com.ecclesiaflow.platform.security.reauth.code.web.EmailCodeConfirmation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecentAuthGateTest {

    private static final String SUB = "3f2c1d4e-1111-4222-8333-944455556666";
    private static final Duration FIVE_MINUTES = Duration.ofSeconds(300);
    private static final PendingConfirmation CODE_SENT = PendingConfirmation.codeSentToCurrentAddress(
            UUID.fromString("0f8d6a3e-5b1c-4d7e-9a2b-3c4d5e6f7a8b"), Instant.parse("2026-11-14T10:10:00Z"),
            "c•••@gmail.com", Instant.parse("2026-11-14T10:01:00Z"));

    @Mock
    private RecentAuthPolicy policy;
    @Mock
    private PendingConfirmationIssuer issuer;
    @Mock
    private EmailCodeConfirmation emailCodes;
    @Mock
    private ObjectProvider<RecentAuthPolicy> policies;
    @Mock
    private ObjectProvider<PendingConfirmationIssuer> issuers;
    @Mock
    private ObjectProvider<EmailCodeConfirmation> confirmations;

    private RecentAuthGate gate;

    @BeforeEach
    void setUp() {
        gate = new RecentAuthGate(policies, issuers, confirmations);
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject(SUB)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, AuthorityUtils.NO_AUTHORITIES));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void verdict(LinkedAccountProof mode, RecentAuthVerdict verdict) {
        when(policies.getObject()).thenReturn(policy);
        when(policy.require(FIVE_MINUTES, mode)).thenReturn(verdict);
    }

    @Nested
    @DisplayName("under CONFIRMATION_LINK, what members and church use")
    class ConfirmationLink {

        @Test
        @DisplayName("lets a recent sign-in through without the issuer")
        void passed() {
            verdict(LinkedAccountProof.CONFIRMATION_LINK, RecentAuthVerdict.PASSED);

            assertThat(gate.hold("EMAIL_CHANGE", FIVE_MINUTES, LinkedAccountProof.CONFIRMATION_LINK, List.of()))
                    .isEmpty();
            verifyNoInteractions(issuers, confirmations);
        }

        @Test
        @DisplayName("holds a linked account's gesture with the issuer's body, its arguments and subject handed over")
        void outOfBand() {
            verdict(LinkedAccountProof.CONFIRMATION_LINK, RecentAuthVerdict.CONFIRM_OUT_OF_BAND);
            PendingConfirmation link = PendingConfirmation.sentToCurrentAddress(Instant.parse("2026-10-22T08:15:00Z"));
            when(issuers.getObject()).thenReturn(issuer);
            when(issuer.issue(any())).thenReturn(link);

            assertThat(gate.hold("EMAIL_CHANGE", FIVE_MINUTES, LinkedAccountProof.CONFIRMATION_LINK,
                    Arrays.asList("new@example.org", null))).contains(link);

            ArgumentCaptor<SensitiveAction> action = ArgumentCaptor.forClass(SensitiveAction.class);
            verify(issuer).issue(action.capture());
            assertThat(action.getValue()).isEqualTo(new SensitiveAction("EMAIL_CHANGE", SUB,
                    Arrays.asList("new@example.org", null)));
            verifyNoInteractions(confirmations);
        }

        @Test
        @DisplayName("fails rather than let the gesture run when the issuer answers no body")
        void noBody() {
            verdict(LinkedAccountProof.CONFIRMATION_LINK, RecentAuthVerdict.CONFIRM_OUT_OF_BAND);
            when(issuers.getObject()).thenReturn(issuer);

            assertThatThrownBy(() -> gate.hold("EMAIL_CHANGE", FIVE_MINUTES, LinkedAccountProof.CONFIRMATION_LINK,
                    List.of())).isInstanceOf(IllegalStateException.class)
                    .hasMessage("The PendingConfirmationIssuer returned no body for EMAIL_CHANGE");
        }
    }

    @Nested
    @DisplayName("under SECOND_FACTOR_OR_EMAIL_CODE")
    class SecondFactorOrEmailCode {

        private static final LinkedAccountProof MODE = LinkedAccountProof.SECOND_FACTOR_OR_EMAIL_CODE;

        @Test
        @DisplayName("lets an attested second factor through without any code")
        void passed() {
            verdict(MODE, RecentAuthVerdict.PASSED);

            assertThat(gate.hold("Subscription.Cancel", FIVE_MINUTES, MODE, List.of())).isEmpty();
            verifyNoInteractions(confirmations, issuers);
        }

        @Test
        @DisplayName("answers the 202 of a code just sent, the gesture held")
        void codeSent() {
            verdict(MODE, RecentAuthVerdict.CONFIRM_BY_EMAIL_CODE);
            when(confirmations.getIfAvailable()).thenReturn(emailCodes);
            when(emailCodes.confirm(new SensitiveAction("Subscription.Cancel", SUB, List.of("church"))))
                    .thenReturn(Optional.of(CODE_SENT));

            assertThat(gate.hold("Subscription.Cancel", FIVE_MINUTES, MODE, List.of("church"))).contains(CODE_SENT);
            verifyNoInteractions(issuers);
        }

        @Test
        @DisplayName("lets the gesture run once the request carried its code")
        void codeVerified() {
            verdict(MODE, RecentAuthVerdict.CONFIRM_BY_EMAIL_CODE);
            when(confirmations.getIfAvailable()).thenReturn(emailCodes);
            when(emailCodes.confirm(any())).thenReturn(Optional.empty());

            assertThat(gate.hold("Subscription.Cancel", FIVE_MINUTES, MODE, List.of())).isEmpty();
        }

        @Test
        @DisplayName("fails closed when the module asks for a code it did not enable")
        void notEnabled() {
            verdict(MODE, RecentAuthVerdict.CONFIRM_BY_EMAIL_CODE);

            assertThatThrownBy(() -> gate.hold("Subscription.Cancel", FIVE_MINUTES, MODE, List.of()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("Subscription.Cancel");
        }

        @Test
        @DisplayName("lets the policy's refusals through to the caller")
        void refused() {
            when(policies.getObject()).thenReturn(policy);
            when(policy.require(FIVE_MINUTES, MODE))
                    .thenThrow(ReauthenticationRequiredException.secondFactorNotPresented());

            assertThatThrownBy(() -> gate.hold("Subscription.Cancel", FIVE_MINUTES, MODE, List.of()))
                    .isInstanceOf(ReauthenticationRequiredException.class);
            verifyNoInteractions(confirmations);
        }
    }

    @Nested
    @DisplayName("for a gesture checked in its delegate")
    class Conditional {

        @Test
        @DisplayName("returns when the gesture may run, judged at 300 s under the code mode")
        void runs() {
            verdict(LinkedAccountProof.SECOND_FACTOR_OR_EMAIL_CODE, RecentAuthVerdict.PASSED);

            assertThatCode(() -> gate.require("Subscription.ChangePlan", "church", "STARTER"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("throws the 202 of a code just sent, before any effect")
        void codeSent() {
            verdict(LinkedAccountProof.SECOND_FACTOR_OR_EMAIL_CODE, RecentAuthVerdict.CONFIRM_BY_EMAIL_CODE);
            when(confirmations.getIfAvailable()).thenReturn(emailCodes);
            when(emailCodes.confirm(new SensitiveAction("Invoices.Pay", SUB, List.of("church", "invoice-7"))))
                    .thenReturn(Optional.of(CODE_SENT));

            ConfirmationCodeSentException held = catchThrowableOfType(ConfirmationCodeSentException.class,
                    () -> gate.require("Invoices.Pay", "church", "invoice-7"));

            assertThat(held.getPending()).isSameAs(CODE_SENT);
        }

        @Test
        @DisplayName("takes no argument at all")
        void noArguments() {
            verdict(LinkedAccountProof.SECOND_FACTOR_OR_EMAIL_CODE, RecentAuthVerdict.CONFIRM_BY_EMAIL_CODE);
            when(confirmations.getIfAvailable()).thenReturn(emailCodes);
            when(emailCodes.confirm(new SensitiveAction("Subscription.Cancel", SUB, List.of())))
                    .thenReturn(Optional.empty());

            assertThatCode(() -> gate.require("Subscription.Cancel", (Object[]) null)).doesNotThrowAnyException();
        }
    }
}
