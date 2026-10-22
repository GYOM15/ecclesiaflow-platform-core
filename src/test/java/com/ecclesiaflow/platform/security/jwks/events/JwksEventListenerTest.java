package com.ecclesiaflow.platform.security.jwks.events;

import nl.altindag.log.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class JwksEventListenerTest {

    private final JwksEventListener listener = new JwksEventListener();
    private final LogCaptor logs = LogCaptor.forClass(JwksEventListener.class);

    @AfterEach
    void tearDown() {
        logs.close();
    }

    @Test
    @DisplayName("the first load and a rotation are INFO, a routine refresh only DEBUG")
    void loaded() {
        logs.setLogLevelToDebug();
        listener.onKeySetLoaded(new JwksEvents.KeySetLoaded(JwksEvents.Trigger.FIRST_LOAD, 2, false));
        listener.onKeySetLoaded(new JwksEvents.KeySetLoaded(JwksEvents.Trigger.UNKNOWN_KEY, 3, false));
        listener.onKeySetLoaded(new JwksEvents.KeySetLoaded(JwksEvents.Trigger.REFRESH, 3, false));

        assertThat(logs.getInfoLogs()).containsExactly(
                "JWKS: ✅ Key set loaded (first load) — 2 key(s)",
                "JWKS: ✅ Key set loaded (unknown key id) — 3 key(s)");
        assertThat(logs.getDebugLogs()).containsExactly("JWKS: Key set refreshed — 3 key(s)");
    }

    @Test
    @DisplayName("the end of an outage is INFO whatever asked for the reload")
    void recovered() {
        listener.onKeySetLoaded(new JwksEvents.KeySetLoaded(JwksEvents.Trigger.REFRESH, 2, true));

        assertThat(logs.getInfoLogs()).containsExactly("JWKS: ✅ Key set reloaded after an outage — 2 key(s)");
    }

    @Test
    @DisplayName("a failure while the last set serves is a WARN with the outage so far, the address masked")
    void servingLastKnown() {
        listener.onReloadFailed(new JwksEvents.ReloadFailed(JwksEvents.Trigger.REFRESH,
                new ConnectException("Connection refused: keycloak:8080"), Duration.ofSeconds(90), true));

        assertThat(logs.getWarnLogs()).singleElement().asString()
                .startsWith("JWKS: ⚠ scheduled reload failed, the last key set still serves (outage 90 s)")
                .contains("ConnectException")
                .doesNotContain("keycloak:8080");
    }

    @Test
    @DisplayName("a first load that fails is an ERROR: no token can be checked")
    void nothingToServe() {
        listener.onReloadFailed(new JwksEvents.ReloadFailed(JwksEvents.Trigger.FIRST_LOAD,
                new ConnectException("Connection refused"), null, false));

        assertThat(logs.getErrorLogs()).singleElement().asString()
                .startsWith("JWKS: ❌ first load failed, no key set to check tokens with");
    }

    @Test
    @DisplayName("an outage past the tolerance is an ERROR saying tokens are refused")
    void beyondTolerance() {
        listener.onReloadFailed(new JwksEvents.ReloadFailed(JwksEvents.Trigger.UNKNOWN_KEY,
                new ConnectException("Connection refused"), Duration.ofHours(24), false));

        assertThat(logs.getErrorLogs()).singleElement().asString()
                .startsWith("JWKS: ❌ unknown key id reload failed and the outage (86400 s) exceeds the tolerance, "
                        + "tokens are refused");
    }
}
