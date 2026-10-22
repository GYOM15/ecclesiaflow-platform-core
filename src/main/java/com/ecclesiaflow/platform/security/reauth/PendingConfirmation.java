package com.ecclesiaflow.platform.security.reauth;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * The 202 body of a gesture waiting for its confirmation link. A success, not an error: clients pick their text
 * from {@code sentTo}, never from a message. {@code status} and {@code sentTo} are open vocabularies
 * ({@code x-extensible-enum}), hence strings.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PendingConfirmation(
        String status,
        String sentTo,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant expiresAt) {

    public static final String CONFIRMATION_SENT = "CONFIRMATION_SENT";
    public static final String CURRENT_ADDRESS = "CURRENT_ADDRESS";

    public static PendingConfirmation sentToCurrentAddress(Instant expiresAt) {
        return new PendingConfirmation(CONFIRMATION_SENT, CURRENT_ADDRESS, expiresAt);
    }
}
