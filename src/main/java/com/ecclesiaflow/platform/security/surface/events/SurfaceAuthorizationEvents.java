package com.ecclesiaflow.platform.security.surface.events;

import com.ecclesiaflow.platform.security.surface.Surface;
import com.ecclesiaflow.platform.security.surface.SurfaceMode;

/** Decisions of the surface filter and verifier, which never log: listeners turn them into logs. */
public final class SurfaceAuthorizationEvents {

    private SurfaceAuthorizationEvents() {
    }

    /**
     * A token that does not open the operation's surface. In {@code log-only} the request went on anyway.
     *
     * @param operation the operation's label, {@code null} outside the contract
     * @param path      the raw path, carried only outside the contract, to be masked before it is logged
     * @param caller    {@code console}, {@code moderation} or {@code other}, from the token's authorities
     * @param azp       the token's client, {@code null} when absent
     * @param subject   the token's subject, already masked
     */
    public record Refused(String method, String operation, String path, Surface surface, SurfaceMode mode,
                          String caller, String azp, String subject) {
    }

    /** Published once at startup so the mode in force is stated in the logs, not implied by a missing property. */
    public record ModeActive(SurfaceMode mode, int memberOperations, int consoleOperations, int platformOperations,
                             int tokenlessOperations) {
    }
}
