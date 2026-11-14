package com.ecclesiaflow.platform.security.reauth;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * The 202 body of a gesture waiting for its confirmation. A success, not an error: clients pick their text from
 * {@code status} and {@code sentTo}, never from a message. {@code status}, {@code sentTo} and {@code channel} are
 * open vocabularies ({@code x-extensible-enum}), hence strings. The fields after {@code expiresAt} come with a
 * code only and are left out when {@code null}, so a link's body keeps its three fields.
 *
 * @param expiresAt         when the link, or the code, stops being valid
 * @param challengeId       what the request replays in {@code Reauth-Challenge}; absent for a link
 * @param channel           where the code went; absent means {@code EMAIL}
 * @param maskedAddress     the address the code went to, masked; absent, the client words it without
 * @param resendAvailableAt from when a new code may be asked for; absent, at once
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PendingConfirmation(
        String status,
        String sentTo,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant expiresAt,
        UUID challengeId,
        String channel,
        String maskedAddress,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant resendAvailableAt) {

    public static final String CONFIRMATION_SENT = "CONFIRMATION_SENT";
    public static final String CODE_SENT = "CODE_SENT";
    public static final String CURRENT_ADDRESS = "CURRENT_ADDRESS";
    public static final String EMAIL = "EMAIL";

    /** A link's body, as members and church answer it. */
    public PendingConfirmation(String status, String sentTo, Instant expiresAt) {
        this(status, sentTo, expiresAt, null, null, null, null);
    }

    public static PendingConfirmation sentToCurrentAddress(Instant expiresAt) {
        return new PendingConfirmation(CONFIRMATION_SENT, CURRENT_ADDRESS, expiresAt);
    }

    /** A code sent by e-mail to the account's current address. */
    public static PendingConfirmation codeSentToCurrentAddress(UUID challengeId, Instant expiresAt,
                                                               String maskedAddress, Instant resendAvailableAt) {
        return new PendingConfirmation(CODE_SENT, CURRENT_ADDRESS, expiresAt, challengeId, EMAIL, maskedAddress,
                resendAvailableAt);
    }
}
