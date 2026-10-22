package com.ecclesiaflow.platform.security.surface;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TechnicalPathsTest {

    @Test
    @DisplayName("the chat socket, the error page, the actuator and the API documentation are technical")
    void technical() {
        assertThat(List.of("/ws", "/ws/info", "/ws/123/abc/websocket", "/error", "/actuator", "/actuator/health",
                "/actuator/health/readiness", "/v3/api-docs", "/v3/api-docs/swagger-config", "/swagger-ui.html",
                "/swagger-ui", "/swagger-ui/index.html")).allMatch(TechnicalPaths::matches);
    }

    @Test
    @DisplayName("nothing else is, not even a lookalike")
    void notTechnical() {
        assertThat(List.of("/", "/error/details", "/errors", "/wsx", "/ecclesiaflow/ws/info", "/v3/api-docsx",
                "/swagger-uix", "/actuatorx/health")).noneMatch(TechnicalPaths::matches);
        assertThat(TechnicalPaths.matches(null)).isFalse();
    }
}
