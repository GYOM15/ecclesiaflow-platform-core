package com.ecclesiaflow.platform.security.surface.events;

import com.ecclesiaflow.platform.security.surface.Surface;
import com.ecclesiaflow.platform.security.surface.SurfaceMode;
import nl.altindag.log.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SurfaceAuthorizationEventListenerTest {

    private final SurfaceAuthorizationEventListener listener = new SurfaceAuthorizationEventListener();
    private final LogCaptor logs = LogCaptor.forClass(SurfaceAuthorizationEventListener.class);

    @AfterEach
    void tearDown() {
        logs.close();
    }

    @Test
    @DisplayName("an enforced refusal is a WARN naming the operation, the surface and the token")
    void enforcedRefusal() {
        listener.onRefused(new SurfaceAuthorizationEvents.Refused("GET", "ConsoleMembers.List", null,
                Surface.CONSOLE, SurfaceMode.ENFORCE, "other", "ecclesiaflow-mobile", "3f2c1d4e********"));

        assertThat(logs.getWarnLogs()).singleElement().asString()
                .startsWith("SURFACE: ❌ Refused ConsoleMembers.List")
                .contains("console surface", "other token", "client=ecclesiaflow-mobile", "subject=3f2c1d4e********");
    }

    @Test
    @DisplayName("a log-only refusal says the request went on")
    void logOnlyRefusal() {
        listener.onRefused(new SurfaceAuthorizationEvents.Refused("GET", "Reports.List", null, Surface.PLATFORM,
                SurfaceMode.LOG_ONLY, "console", null, "3f2c1d4e********"));

        assertThat(logs.getWarnLogs()).singleElement().asString()
                .startsWith("SURFACE: ⚠ Would refuse Reports.List (log-only)")
                .contains("platform surface", "console token", "client=<no azp>");
    }

    @Test
    @DisplayName("a path outside the contract is logged with its ids masked and its control characters escaped")
    void maskedPath() {
        String id = "3f2c1d4e-1111-4222-8333-944455556666";
        listener.onRefused(new SurfaceAuthorizationEvents.Refused("GET", null,
                "/members/" + id + "/x\r\nINFO forged", Surface.CONSOLE, SurfaceMode.ENFORCE, "other",
                "client\nforged", "3f2c1d4e********"));

        assertThat(logs.getWarnLogs()).singleElement().asString()
                .contains("GET /members/3f2c1d4e********/x\\r\\nINFO forged (outside the contract)")
                .contains("client=client\\nforged")
                .doesNotContain(id)
                .doesNotContain("\n");
    }

    @Test
    @DisplayName("enforce is announced at INFO with the operations it rules")
    void enforceAnnounced() {
        listener.onModeActive(new SurfaceAuthorizationEvents.ModeActive(SurfaceMode.ENFORCE, 3, 5, 1, 2));

        assertThat(logs.getInfoLogs()).singleElement().asString()
                .isEqualTo("SURFACE: ✅ enforce — 3 member, 5 console, 1 platform operations ruled; 2 without a token");
    }

    @Test
    @DisplayName("log-only is announced at WARN, with how to leave it")
    void logOnlyAnnounced() {
        listener.onModeActive(new SurfaceAuthorizationEvents.ModeActive(SurfaceMode.LOG_ONLY, 3, 5, 1, 2));

        assertThat(logs.getWarnLogs()).singleElement().asString()
                .startsWith("SURFACE: ⚠ log-only")
                .contains("ecclesiaflow.security.surface.mode=enforce");
    }
}
