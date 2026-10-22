package com.ecclesiaflow.platform.security.reauth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecentAuthAspectTest {

    private static final String SUB = "3f2c1d4e-1111-4222-8333-944455556666";

    @Mock
    private RecentAuthPolicy policy;
    @Mock
    private PendingConfirmationIssuer issuer;
    @Mock
    private ObjectProvider<RecentAuthPolicy> policies;
    @Mock
    private ObjectProvider<PendingConfirmationIssuer> issuers;

    private final AccountGestures target = new AccountGestures();
    private AccountGestures gestures;

    /** Stands for a module's delegate. */
    static class AccountGestures {

        final AtomicInteger runs = new AtomicInteger();

        @RecentAuthRequired(action = "EMAIL_CHANGE")
        public ResponseEntity<String> changeEmail(String newAddress, Integer unused) {
            runs.incrementAndGet();
            return ResponseEntity.ok("changed to " + newAddress);
        }

        @RecentAuthRequired(action = "ACCOUNT_REACTIVATION", maxAgeSeconds = 600)
        public ResponseEntity<String> reactivate() {
            runs.incrementAndGet();
            return ResponseEntity.ok("reactivated");
        }

        public ResponseEntity<String> unguarded() {
            runs.incrementAndGet();
            return ResponseEntity.ok("free");
        }
    }

    @BeforeEach
    void proxy() {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new RecentAuthAspect(policies, issuers));
        gestures = factory.getProxy();
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject(SUB)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, AuthorityUtils.NO_AUTHORITIES));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a recent sign-in runs the gesture, with the annotation's limit")
    void passed() {
        when(policies.getObject()).thenReturn(policy);
        when(policy.require(Duration.ofSeconds(300))).thenReturn(RecentAuthVerdict.PASSED);

        assertThat(gestures.changeEmail("new@example.org", null).getBody()).isEqualTo("changed to new@example.org");
        assertThat(target.runs).hasValue(1);
        verifyNoInteractions(issuers);
    }

    @Test
    @DisplayName("a reactivation is judged against its own 600 s")
    void ownLimit() {
        when(policies.getObject()).thenReturn(policy);
        when(policy.require(Duration.ofSeconds(600))).thenReturn(RecentAuthVerdict.PASSED);

        assertThat(gestures.reactivate().getBody()).isEqualTo("reactivated");
    }

    @Test
    @DisplayName("a linked account answers 202 with the issuer's body and the gesture does not run")
    void confirmOutOfBand() {
        PendingConfirmation pending = PendingConfirmation.sentToCurrentAddress(Instant.parse("2026-10-22T08:15:00Z"));
        when(policies.getObject()).thenReturn(policy);
        when(policy.require(Duration.ofSeconds(300))).thenReturn(RecentAuthVerdict.CONFIRM_OUT_OF_BAND);
        when(issuers.getObject()).thenReturn(issuer);
        when(issuer.issue(any())).thenReturn(pending);

        ResponseEntity<String> answer = gestures.changeEmail("new@example.org", null);

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat((Object) answer.getBody()).isEqualTo(pending);
        assertThat(target.runs).hasValue(0);
        ArgumentCaptor<SensitiveAction> action = ArgumentCaptor.forClass(SensitiveAction.class);
        verify(issuer).issue(action.capture());
        assertThat(action.getValue().name()).isEqualTo("EMAIL_CHANGE");
        assertThat(action.getValue().subject()).isEqualTo(SUB);
        assertThat(action.getValue().arguments()).containsExactly("new@example.org", null);
        assertThat(action.getValue().argument(String.class)).contains("new@example.org");
    }

    @Test
    @DisplayName("an issuer that answers no body fails the call rather than letting the gesture run")
    void noBody() {
        when(policies.getObject()).thenReturn(policy);
        when(policy.require(Duration.ofSeconds(300))).thenReturn(RecentAuthVerdict.CONFIRM_OUT_OF_BAND);
        when(issuers.getObject()).thenReturn(issuer);

        assertThatThrownBy(() -> gestures.changeEmail("new@example.org", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EMAIL_CHANGE");
        assertThat(target.runs).hasValue(0);
    }

    @Test
    @DisplayName("a refusal reaches the caller and the gesture does not run")
    void refused() {
        when(policies.getObject()).thenReturn(policy);
        when(policy.require(Duration.ofSeconds(300))).thenThrow(ReauthenticationRequiredException.signInTooOld());

        assertThatThrownBy(() -> gestures.changeEmail("new@example.org", null))
                .isInstanceOf(ReauthenticationRequiredException.class);
        assertThat(target.runs).hasValue(0);
    }

    @Test
    @DisplayName("unreadable sign-in methods reach the caller and the gesture does not run")
    void unavailable() {
        when(policies.getObject()).thenReturn(policy);
        when(policy.require(Duration.ofSeconds(300)))
                .thenThrow(new SignInMethodsUnavailableException(new IllegalStateException("down")));

        assertThatThrownBy(() -> gestures.changeEmail("new@example.org", null))
                .isInstanceOf(SignInMethodsUnavailableException.class);
        assertThat(target.runs).hasValue(0);
    }

    @Test
    @DisplayName("a method without the annotation is left alone")
    void unguarded() {
        assertThat(gestures.unguarded().getBody()).isEqualTo("free");
        verifyNoInteractions(policies, issuers);
    }
}
