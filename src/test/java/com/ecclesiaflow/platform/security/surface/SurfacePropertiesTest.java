package com.ecclesiaflow.platform.security.surface;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SurfacePropertiesTest {

    @Test
    @DisplayName("enforce unless log-only is set, and an emptied value falls back to enforce")
    void closedByDefault() {
        SurfaceProperties properties = new SurfaceProperties();
        assertThat(properties.getMode()).isEqualTo(SurfaceMode.ENFORCE);

        properties.setMode(SurfaceMode.LOG_ONLY);
        assertThat(properties.getMode()).isEqualTo(SurfaceMode.LOG_ONLY);

        properties.setMode(null);
        assertThat(properties.getMode()).isEqualTo(SurfaceMode.ENFORCE);
    }

    @Test
    @DisplayName("the modes carry the property values")
    void tags() {
        assertThat(SurfaceMode.ENFORCE.tag()).isEqualTo("enforce");
        assertThat(SurfaceMode.LOG_ONLY.tag()).isEqualTo("log-only");
    }
}
