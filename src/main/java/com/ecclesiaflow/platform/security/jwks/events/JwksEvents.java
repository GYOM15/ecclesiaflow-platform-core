package com.ecclesiaflow.platform.security.jwks.events;

import java.time.Duration;

/** Reloads of the realm's key set, published by the key source, which never logs: listeners turn them into logs. */
public final class JwksEvents {

    private JwksEvents() {
    }

    /** What asked for the reload. */
    public enum Trigger {
        FIRST_LOAD,
        REFRESH,
        UNKNOWN_KEY
    }

    /** @param afterFailure the previous attempt had failed: the outage is over */
    public record KeySetLoaded(Trigger trigger, int keyCount, boolean afterFailure) {
    }

    /**
     * @param outage           time since reloads started failing; {@code null} when no key set was ever loaded
     * @param servingLastKnown the last loaded set still decodes tokens; {@code false} once nothing is left to serve
     */
    public record ReloadFailed(Trigger trigger, Throwable cause, Duration outage, boolean servingLastKnown) {
    }
}
