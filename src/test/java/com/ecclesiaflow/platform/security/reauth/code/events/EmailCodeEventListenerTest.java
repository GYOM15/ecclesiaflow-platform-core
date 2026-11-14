package com.ecclesiaflow.platform.security.reauth.code.events;

import nl.altindag.log.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EmailCodeEventListenerTest {

    private static final String SUB = "3f2c1d4e-1111-4222-8333-944455556666";
    private static final UUID CHALLENGE = UUID.fromString("0f8d6a3e-5b1c-4d7e-9a2b-3c4d5e6f7a8b");

    private final EmailCodeEventListener listener = new EmailCodeEventListener();
    private final LogCaptor logs = LogCaptor.forClass(EmailCodeEventListener.class);

    @AfterEach
    void tearDown() {
        logs.close();
    }

    @Test
    @DisplayName("a code sent is INFO, the subject and the challenge masked")
    void sent() {
        listener.onSent(new EmailCodeEvents.Sent(SUB, CHALLENGE, "Subscription.Cancel", false));
        listener.onSent(new EmailCodeEvents.Sent(SUB, CHALLENGE, "Subscription.Cancel", true));

        assertThat(logs.getInfoLogs()).containsExactly(
                "REAUTH-CODE: ✅ First code sent for Subscription.Cancel (challenge=0f8d6a3e********, "
                        + "subject=3f2c1d4e********)",
                "REAUTH-CODE: ✅ New code sent for Subscription.Cancel (challenge=0f8d6a3e********, "
                        + "subject=3f2c1d4e********)");
    }

    @Test
    @DisplayName("a code reused is only DEBUG")
    void reused() {
        logs.setLogLevelToDebug();

        listener.onReused(new EmailCodeEvents.Reused(SUB, CHALLENGE, "Subscription.Cancel"));

        assertThat(logs.getDebugLogs()).singleElement().asString().contains("nothing sent")
                .doesNotContain(SUB, CHALLENGE.toString());
    }

    @Test
    @DisplayName("a code not sent is a WARN, the failure's address and any control character kept out")
    void notSent() {
        listener.onNotSent(new EmailCodeEvents.NotSent(SUB, "Subscription.Cancel",
                EmailCodeEvents.NotSentReason.NO_ADDRESS, null));
        listener.onNotSent(new EmailCodeEvents.NotSent(SUB, "Subscription.Cancel",
                EmailCodeEvents.NotSentReason.DELIVERY_FAILED,
                new IllegalStateException("caleb@gmail.com rejected by communication:9090\nINFO forged")));

        assertThat(logs.getWarnLogs()).hasSize(2);
        assertThat(logs.getWarnLogs().get(0)).isEqualTo("REAUTH-CODE: ❌ Code not sent for Subscription.Cancel, "
                + "NO_ADDRESS — answered 503 (subject=3f2c1d4e********)");
        assertThat(logs.getWarnLogs().get(1)).contains("DELIVERY_FAILED — IllegalStateException")
                .doesNotContain("caleb@gmail.com", "communication:9090", "\n");
    }

    @Test
    @DisplayName("an accepted code is INFO, and says when it passed again for the same keyed request")
    void verified() {
        listener.onVerified(new EmailCodeEvents.Verified(SUB, CHALLENGE, "Invoices.Pay", false));
        listener.onVerified(new EmailCodeEvents.Verified(SUB, CHALLENGE, "Invoices.Pay", true));

        assertThat(logs.getInfoLogs()).containsExactly(
                "REAUTH-CODE: ✅ Code accepted for Invoices.Pay (challenge=0f8d6a3e********, subject=3f2c1d4e********)",
                "REAUTH-CODE: ✅ Code accepted again, same request and key for Invoices.Pay "
                        + "(challenge=0f8d6a3e********, subject=3f2c1d4e********)");
    }

    @Test
    @DisplayName("refusals are INFO with their counts or reason, a lock is a WARN with its delay")
    void refusals() {
        listener.onInvalid(new EmailCodeEvents.Invalid(SUB, CHALLENGE, 2, 7));
        listener.onMalformed(new EmailCodeEvents.Malformed(SUB, "Subscription.Cancel"));
        listener.onExpired(new EmailCodeEvents.Expired(SUB, CHALLENGE, EmailCodeEvents.ExpiryReason.WORN_OUT));
        listener.onSuperseded(new EmailCodeEvents.Superseded(SUB, CHALLENGE,
                EmailCodeEvents.SupersededBy.OTHER_REQUEST));
        listener.onSendThrottled(new EmailCodeEvents.SendThrottled(SUB, "reauth-email-code-hour", 3420));
        listener.onLocked(new EmailCodeEvents.Locked(SUB, 86_000));

        assertThat(logs.getInfoLogs()).containsExactly(
                "REAUTH-CODE: ❌ Wrong code — 2 on this code, 7 on the account in 24 h (challenge=0f8d6a3e********, "
                        + "subject=3f2c1d4e********)",
                "REAUTH-CODE: ❌ Malformed confirmation headers on Subscription.Cancel, not counted "
                        + "(subject=3f2c1d4e********)",
                "REAUTH-CODE: ❌ Code no longer valid (WORN_OUT) (challenge=0f8d6a3e********, subject=3f2c1d4e********)",
                "REAUTH-CODE: Challenge replaced (OTHER_REQUEST) (challenge=0f8d6a3e********, subject=3f2c1d4e********)",
                "REAUTH-CODE: ⏳ No new code before 3420 s (reauth-email-code-hour) (subject=3f2c1d4e********)");
        assertThat(logs.getWarnLogs()).containsExactly("REAUTH-CODE: ⚠ Account locked out of codes for 86000 s after "
                + "ten wrong codes (subject=3f2c1d4e********)");
    }
}
