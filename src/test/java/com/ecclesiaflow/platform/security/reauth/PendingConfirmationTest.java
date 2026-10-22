package com.ecclesiaflow.platform.security.reauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PendingConfirmationTest {

    private final ObjectMapper json = JsonMapper.builder().addModule(new JavaTimeModule()).build();

    @Test
    @DisplayName("is the body of ARCHITECTURE-MOBILE §4.11, its instant an ISO string")
    void body() throws Exception {
        PendingConfirmation pending = PendingConfirmation.sentToCurrentAddress(Instant.parse("2026-10-22T08:15:00Z"));

        assertThat(json.writeValueAsString(pending)).isEqualTo(
                "{\"status\":\"CONFIRMATION_SENT\",\"sentTo\":\"CURRENT_ADDRESS\",\"expiresAt\":\"2026-10-22T08:15:00Z\"}");
    }

    @Test
    @DisplayName("leaves out what it does not know")
    void withoutExpiry() throws Exception {
        assertThat(json.writeValueAsString(new PendingConfirmation("CONFIRMATION_SENT", "CURRENT_ADDRESS", null)))
                .isEqualTo("{\"status\":\"CONFIRMATION_SENT\",\"sentTo\":\"CURRENT_ADDRESS\"}");
    }
}
