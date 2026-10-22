package com.ecclesiaflow.platform.security.surface.events;

import com.ecclesiaflow.platform.logging.SecurityMaskingUtils;
import com.ecclesiaflow.platform.security.surface.SurfaceMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;

/**
 * WARN for every refusal, enforced or not: in {@code log-only} these lines are what decides the switch to
 * {@code enforce}, and a console or moderation token refused there holds the switch back.
 */
@Slf4j
public class SurfaceAuthorizationEventListener {

    private static final int MAX_PATH_LENGTH = 200;

    @EventListener
    public void onRefused(SurfaceAuthorizationEvents.Refused event) {
        String target = event.operation() != null
                ? event.operation()
                : event.method() + " " + maskedPath(event.path()) + " (outside the contract)";
        if (event.mode() == SurfaceMode.ENFORCE) {
            log.warn("SURFACE: ❌ Refused {} — {} surface, {} token, client={} subject={}",
                    target, event.surface().tag(), event.caller(), clientOf(event.azp()), event.subject());
            return;
        }
        log.warn("SURFACE: ⚠ Would refuse {} (log-only) — {} surface, {} token, client={} subject={}",
                target, event.surface().tag(), event.caller(), clientOf(event.azp()), event.subject());
    }

    @EventListener
    public void onModeActive(SurfaceAuthorizationEvents.ModeActive event) {
        if (event.mode() == SurfaceMode.ENFORCE) {
            log.info("SURFACE: ✅ enforce — {} member, {} console, {} platform operations ruled; {} without a token",
                    event.memberOperations(), event.consoleOperations(), event.platformOperations(),
                    event.tokenlessOperations());
            return;
        }
        log.warn("SURFACE: ⚠ log-only — refusals are logged and let through ({} member, {} console, {} platform "
                        + "operations); set ecclesiaflow.security.surface.mode=enforce once no web refusal shows",
                event.memberOperations(), event.consoleOperations(), event.platformOperations());
    }

    // A path outside the contract is the caller's text: ids or an address in it are personal data, and
    // control characters would forge log lines.
    private static String maskedPath(String path) {
        return SecurityMaskingUtils.sanitizeInfra(
                SecurityMaskingUtils.escapeControlChars(SecurityMaskingUtils.abbreviate(path, MAX_PATH_LENGTH)));
    }

    private static String clientOf(String azp) {
        return azp == null ? "<no azp>" : SecurityMaskingUtils.escapeControlChars(azp);
    }
}
