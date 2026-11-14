package com.ecclesiaflow.platform.security.reauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

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

    @Test
    @DisplayName("keeps a link's body at three fields, byte for byte, whichever constructor builds it")
    void linkBodyUnchanged() throws Exception {
        Instant expiresAt = Instant.parse("2026-10-22T08:15:00Z");
        String link = "{\"status\":\"CONFIRMATION_SENT\",\"sentTo\":\"CURRENT_ADDRESS\",\"expiresAt\":\"2026-10-22T08:15:00Z\"}";

        assertThat(json.writeValueAsString(new PendingConfirmation("CONFIRMATION_SENT", "CURRENT_ADDRESS", expiresAt)))
                .isEqualTo(link);
        assertThat(json.writeValueAsString(new PendingConfirmation("CONFIRMATION_SENT", "CURRENT_ADDRESS", expiresAt,
                null, null, null, null))).isEqualTo(link);
        assertThat(PendingConfirmation.sentToCurrentAddress(expiresAt))
                .isEqualTo(new PendingConfirmation("CONFIRMATION_SENT", "CURRENT_ADDRESS", expiresAt));
    }

    @Test
    @DisplayName("a code's body adds what the dialog needs, its instants ISO strings")
    void codeBody() throws Exception {
        PendingConfirmation pending = PendingConfirmation.codeSentToCurrentAddress(
                UUID.fromString("0f8d6a3e-5b1c-4d7e-9a2b-3c4d5e6f7a8b"), Instant.parse("2026-11-14T10:10:00Z"),
                "c\u2022\u2022\u2022@gmail.com", Instant.parse("2026-11-14T10:01:00Z"));

        assertThat(json.writeValueAsString(pending)).isEqualTo("{\"status\":\"CODE_SENT\",\"sentTo\":\"CURRENT_ADDRESS\","
                + "\"expiresAt\":\"2026-11-14T10:10:00Z\",\"challengeId\":\"0f8d6a3e-5b1c-4d7e-9a2b-3c4d5e6f7a8b\","
                + "\"channel\":\"EMAIL\",\"maskedAddress\":\"c\u2022\u2022\u2022@gmail.com\","
                + "\"resendAvailableAt\":\"2026-11-14T10:01:00Z\"}");
    }

    @Test
    @DisplayName("a code's body without an address to show leaves the field out")
    void codeBodyWithoutAddress() throws Exception {
        PendingConfirmation pending = PendingConfirmation.codeSentToCurrentAddress(
                UUID.fromString("0f8d6a3e-5b1c-4d7e-9a2b-3c4d5e6f7a8b"), Instant.parse("2026-11-14T10:10:00Z"),
                null, Instant.parse("2026-11-14T10:01:00Z"));

        assertThat(json.writeValueAsString(pending)).doesNotContain("maskedAddress").contains("\"channel\":\"EMAIL\"");
    }
}
