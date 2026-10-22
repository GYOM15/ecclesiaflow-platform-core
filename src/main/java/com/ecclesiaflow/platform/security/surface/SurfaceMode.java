package com.ecclesiaflow.platform.security.surface;

/** Bound from {@code ecclesiaflow.security.surface.mode}: {@code enforce} or {@code log-only}. */
public enum SurfaceMode {

    /** A token that does not open the operation's surface is refused with 403. */
    ENFORCE("enforce"),

    /** The refusal is logged and counted, and the request goes on: a transition setting, never a default. */
    LOG_ONLY("log-only");

    private final String tag;

    SurfaceMode(String tag) {
        this.tag = tag;
    }

    /** The property value, also the {@code mode} tag of {@code surface_mode}. */
    public String tag() {
        return tag;
    }
}
