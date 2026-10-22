package com.ecclesiaflow.platform.security.reauth.events;

import com.ecclesiaflow.platform.security.reauth.ReauthenticationRequiredException;
import io.grpc.Status;
import nl.altindag.log.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecentAuthEventListenerTest {

    private static final String SUBJECT = "3f2c1d4e********";

    private final RecentAuthEventListener listener = new RecentAuthEventListener();
    private final LogCaptor logs = LogCaptor.forClass(RecentAuthEventListener.class);

    @AfterEach
    void tearDown() {
        logs.close();
    }

    @Test
    @DisplayName("a pass is only DEBUG")
    void passed() {
        logs.setLogLevelToDebug();

        listener.onPassed(new RecentAuthEvents.Passed(SUBJECT, 42, 300));

        assertThat(logs.getDebugLogs())
                .containsExactly("REAUTH: Recent sign-in accepted — 42 s old, limit 300 s, subject=3f2c1d4e********");
    }

    @Test
    @DisplayName("a refusal is INFO with its reason, the sign-in's age and the limit")
    void refused() {
        listener.onRefused(new RecentAuthEvents.Refused(SUBJECT,
                ReauthenticationRequiredException.Reason.SIGN_IN_TOO_OLD, 412L, 300));
        listener.onRefused(new RecentAuthEvents.Refused(SUBJECT,
                ReauthenticationRequiredException.Reason.NO_SIGN_IN_TIME, null, 600));

        assertThat(logs.getInfoLogs()).containsExactly(
                "REAUTH: ❌ Sign-in required again (SIGN_IN_TOO_OLD) — sign-in 412 s, limit 300 s, "
                        + "subject=3f2c1d4e********",
                "REAUTH: ❌ Sign-in required again (NO_SIGN_IN_TIME) — sign-in unknown, limit 600 s, "
                        + "subject=3f2c1d4e********");
    }

    @Test
    @DisplayName("a confirmation is INFO naming the providers, their control characters escaped")
    void confirmationRequired() {
        listener.onConfirmationRequired(new RecentAuthEvents.ConfirmationRequired(SUBJECT,
                List.of("google", "idp\nINFO forged")));

        assertThat(logs.getInfoLogs()).singleElement().asString()
                .isEqualTo("REAUTH: ⚠ Account linked to google, idp\\nINFO forged — gesture held for a confirmation "
                        + "link, subject=3f2c1d4e********");
    }

    @Test
    @DisplayName("unreadable sign-in methods are a WARN, the address in the cause masked")
    void unavailable() {
        listener.onSignInMethodsUnavailable(new RecentAuthEvents.SignInMethodsUnavailable(SUBJECT,
                Status.UNAVAILABLE.withDescription("auth:9090 unreachable").asRuntimeException()));

        assertThat(logs.getWarnLogs()).singleElement().asString()
                .startsWith("REAUTH: ❌ Sign-in methods unreadable, gesture refused (503) — StatusRuntimeException")
                .contains("subject=3f2c1d4e********")
                .doesNotContain("auth:9090");
    }
}
