package com.ecclesiaflow.platform.security.surface;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OperatingClientsTest {

    private final OperatingClients clients = new OperatingClients(SurfaceProperties.DEFAULT_OPERATING_CLIENTS);

    @Test
    @DisplayName("an operating client is admitted to every platform duty and to nothing else")
    void platformOnly() {
        for (String azp : SurfaceProperties.DEFAULT_OPERATING_CLIENTS) {
            assertThat(List.of(Surface.values())).filteredOn(surface -> clients.admit(azp, surface))
                    .as(azp)
                    .containsExactly(Surface.PLATFORM, Surface.PLATFORM_BILLING, Surface.PLATFORM_SUPPORT,
                            Surface.PLATFORM_ADMINISTRATION);
        }
    }

    @Test
    @DisplayName("any other client, or a token without azp, is left to the surface's own rule")
    void otherClients() {
        for (String azp : new String[]{"ecclesiaflow-frontend", "ecclesiaflow-mobile", null}) {
            assertThat(List.of(Surface.values())).allMatch(surface -> clients.admit(azp, surface));
        }
    }

    @Test
    @DisplayName("the list is copied: a later change to its source opens nothing")
    void copied() {
        List<String> source = new ArrayList<>(List.of("ecclesiaflow-backoffice"));
        OperatingClients copied = new OperatingClients(source);

        source.clear();

        assertThat(copied.clients()).containsExactly("ecclesiaflow-backoffice");
        assertThat(copied.admit("ecclesiaflow-backoffice", Surface.MEMBER)).isFalse();
    }
}
