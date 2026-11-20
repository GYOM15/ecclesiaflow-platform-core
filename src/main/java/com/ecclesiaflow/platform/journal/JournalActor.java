package com.ecclesiaflow.platform.journal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Who made the gesture: the operator's {@code sub}, the platform roles the token carried, sorted, and a digest of the
 * Keycloak session, which ties the gestures of one session together without holding a value that could replay it.
 *
 * @param sessionDigest lowercase SHA-256 hex of the {@code sid} claim, {@code null} when the token carries none
 */
public record JournalActor(String subject, List<String> roles, String sessionDigest) {

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");
    private static final int MAX_SUBJECT = 64;

    public JournalActor {
        subject = JournalValues.ref("actorSubject", subject, MAX_SUBJECT, true);
        roles = roles == null ? List.of() : roles.stream()
                .map(role -> JournalValues.code("actorRoles", role, true))
                .distinct()
                .sorted()
                .toList();
        if (sessionDigest != null && !SHA256_HEX.matcher(sessionDigest).matches()) {
            throw InvalidJournalEntryException.malformed("actorSession");
        }
    }

    /**
     * @param roles     the platform roles of the token, without the {@code ROLE_} prefix
     * @param sessionId the raw {@code sid} claim, hashed here; {@code null} or blank when the token has none
     */
    public static JournalActor of(String subject, Collection<String> roles, String sessionId) {
        return new JournalActor(subject, roles == null ? null : new ArrayList<>(roles),
                sessionId == null || sessionId.isBlank() ? null : sha256Hex(sessionId));
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Objects.requireNonNull(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", impossible);
        }
    }
}
