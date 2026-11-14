package com.ecclesiaflow.platform.security.reauth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecentAuthAspectTest {

    private static final Duration FIVE_MINUTES = Duration.ofSeconds(300);

    @Mock
    private RecentAuthGate gate;
    @Mock
    private ObjectProvider<RecentAuthGate> gates;

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

        @RecentAuthRequired(action = "Subscription.Cancel",
                linkedAccounts = LinkedAccountProof.SECOND_FACTOR_OR_EMAIL_CODE)
        public ResponseEntity<String> cancel(String church) {
            runs.incrementAndGet();
            return ResponseEntity.ok("canceled");
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
        factory.addAspect(new RecentAuthAspect(gates));
        gestures = factory.getProxy();
    }

    @Test
    @DisplayName("runs the gesture the gate lets through, with the annotation's action, limit, mode and arguments")
    void passed() {
        when(gates.getObject()).thenReturn(gate);
        when(gate.hold("EMAIL_CHANGE", FIVE_MINUTES, LinkedAccountProof.CONFIRMATION_LINK,
                Arrays.asList("new@example.org", null))).thenReturn(Optional.empty());

        assertThat(gestures.changeEmail("new@example.org", null).getBody()).isEqualTo("changed to new@example.org");
        assertThat(target.runs).hasValue(1);
    }

    @Test
    @DisplayName("hands a reactivation its own 600 s, and a gesture confirmed by code its mode")
    void ownLimitAndMode() {
        when(gates.getObject()).thenReturn(gate);
        when(gate.hold("ACCOUNT_REACTIVATION", Duration.ofSeconds(600), LinkedAccountProof.CONFIRMATION_LINK,
                List.of())).thenReturn(Optional.empty());
        when(gate.hold("Subscription.Cancel", FIVE_MINUTES, LinkedAccountProof.SECOND_FACTOR_OR_EMAIL_CODE,
                List.of("church"))).thenReturn(Optional.empty());

        assertThat(gestures.reactivate().getBody()).isEqualTo("reactivated");
        assertThat(gestures.cancel("church").getBody()).isEqualTo("canceled");
    }

    @Test
    @DisplayName("answers 202 with the gate's body and the gesture does not run")
    void held() {
        PendingConfirmation pending = PendingConfirmation.sentToCurrentAddress(Instant.parse("2026-10-22T08:15:00Z"));
        when(gates.getObject()).thenReturn(gate);
        when(gate.hold("EMAIL_CHANGE", FIVE_MINUTES, LinkedAccountProof.CONFIRMATION_LINK,
                Arrays.asList("new@example.org", null))).thenReturn(Optional.of(pending));

        ResponseEntity<String> answer = gestures.changeEmail("new@example.org", null);

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat((Object) answer.getBody()).isEqualTo(pending);
        assertThat(target.runs).hasValue(0);
    }

    @Test
    @DisplayName("a refusal reaches the caller and the gesture does not run")
    void refused() {
        when(gates.getObject()).thenReturn(gate);
        when(gate.hold("EMAIL_CHANGE", FIVE_MINUTES, LinkedAccountProof.CONFIRMATION_LINK,
                Arrays.asList("new@example.org", null))).thenThrow(ReauthenticationRequiredException.signInTooOld());

        assertThatThrownBy(() -> gestures.changeEmail("new@example.org", null))
                .isInstanceOf(ReauthenticationRequiredException.class);
        assertThat(target.runs).hasValue(0);
    }

    @Test
    @DisplayName("a method without the annotation is left alone")
    void unguarded() {
        assertThat(gestures.unguarded().getBody()).isEqualTo("free");
        verifyNoInteractions(gates);
    }
}
