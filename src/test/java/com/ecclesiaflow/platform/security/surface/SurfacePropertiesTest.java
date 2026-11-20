package com.ecclesiaflow.platform.security.surface;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

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
    @DisplayName("the back-office and the two command lines reach platform operations only, unless configured")
    void operatingClients() {
        SurfaceProperties properties = new SurfaceProperties();
        assertThat(properties.getOperatingClients()).containsExactly(
                "ecclesiaflow-backoffice", "ecclesiaflow-moderation-cli", "ecclesiaflow-billing-cli");

        properties.setOperatingClients(List.of(" ecclesiaflow-backoffice ", "ecclesiaflow-ops", "ecclesiaflow-ops"));
        assertThat(properties.getOperatingClients()).containsExactly("ecclesiaflow-backoffice", "ecclesiaflow-ops");
    }

    @Test
    @DisplayName("an emptied list keeps the default: no configuration opens member operations to an operating client")
    void emptiedOperatingClients() {
        SurfaceProperties properties = new SurfaceProperties();

        properties.setOperatingClients(null);
        assertThat(properties.getOperatingClients()).isEqualTo(SurfaceProperties.DEFAULT_OPERATING_CLIENTS);

        properties.setOperatingClients(Arrays.asList("", "  ", null));
        assertThat(properties.getOperatingClients()).isEqualTo(SurfaceProperties.DEFAULT_OPERATING_CLIENTS);
    }

    @Test
    @DisplayName("the modes carry the property values")
    void tags() {
        assertThat(SurfaceMode.ENFORCE.tag()).isEqualTo("enforce");
        assertThat(SurfaceMode.LOG_ONLY.tag()).isEqualTo("log-only");
    }
}
