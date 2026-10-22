package com.ecclesiaflow.platform.security.surface;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound to {@code ecclesiaflow.security.surface.*}. Closed by default: a module that sets nothing enforces its
 * surface, and {@code log-only} exists only for a module the deploy lists while it is being switched over.
 */
@ConfigurationProperties(prefix = "ecclesiaflow.security.surface")
public class SurfaceProperties {

    private SurfaceMode mode = SurfaceMode.ENFORCE;

    public SurfaceMode getMode() {
        return mode;
    }

    /** A blank value keeps {@code enforce}: an emptied variable must not open anything. */
    public void setMode(SurfaceMode mode) {
        this.mode = mode == null ? SurfaceMode.ENFORCE : mode;
    }
}
