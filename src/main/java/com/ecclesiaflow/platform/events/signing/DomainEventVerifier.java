package com.ecclesiaflow.platform.events.signing;

import java.time.Clock;
import java.time.Duration;

/**
 * Verification policy for an inbound domain event signature.
 *
 * <p>Signing disabled: always {@link Decision#ACCEPT}. Lenient ({@code verify-signatures=false}, the
 * default): anything but a valid, fresh signature is {@link Decision#ACCEPT_UNVERIFIED}, so a consumer
 * can ship before its publishers sign. Strict: every failure is a {@code REJECT_*} decision.</p>
 *
 * <p>Pass the exchange and routing key the broker delivered on ({@code getReceivedExchange()},
 * {@code getReceivedRoutingKey()}), never values read from a header: the publisher chooses headers, so
 * checking against them would only prove that a forger agreed with themselves.</p>
 */
public class DomainEventVerifier {

    public static final Duration DEFAULT_FRESHNESS_WINDOW = Duration.ofMinutes(5);

    public enum Decision {
        ACCEPT(true),
        /** Deliver, but log a warning. */
        ACCEPT_UNVERIFIED(true),
        REJECT_MISSING(false),
        REJECT_INVALID(false),
        /** Matched but outside the freshness window: a replay, or a clock badly out of step. */
        REJECT_STALE(false),
        /** The publisher was upgraded before this consumer. */
        REJECT_UNSUPPORTED_VERSION(false);

        private final boolean accepted;

        Decision(boolean accepted) {
            this.accepted = accepted;
        }

        public boolean isAccepted() {
            return accepted;
        }
    }

    private final DomainEventSigner signer;
    private final boolean verifySignatures;
    private final Duration freshnessWindow;
    private final Clock clock;

    public DomainEventVerifier(DomainEventSigner signer, boolean verifySignatures) {
        this(signer, verifySignatures, DEFAULT_FRESHNESS_WINDOW, Clock.systemUTC());
    }

    /** The freshness window is symmetric: the fleet's clocks are NTP-synced but not identical. */
    public DomainEventVerifier(DomainEventSigner signer, boolean verifySignatures,
                               Duration freshnessWindow, Clock clock) {
        this.signer = signer;
        this.verifySignatures = verifySignatures;
        this.freshnessWindow = freshnessWindow;
        this.clock = clock;
    }

    public Decision verify(String exchange, String routingKey, byte[] body,
                           String signatureHeader, String signedAtHeader) {
        return verify(exchange, routingKey, body, signatureHeader, signedAtHeader, null);
    }

    /** A {@code null} {@code versionHeader} means version 1. */
    public Decision verify(String exchange, String routingKey, byte[] body,
                           String signatureHeader, String signedAtHeader, String versionHeader) {
        if (!signer.isEnabled()) {
            return Decision.ACCEPT;
        }

        boolean hasSignature = signatureHeader != null && !signatureHeader.isBlank();
        if (!hasSignature) {
            return verifySignatures ? Decision.REJECT_MISSING : Decision.ACCEPT_UNVERIFIED;
        }

        if (!isSupportedVersion(versionHeader)) {
            return verifySignatures ? Decision.REJECT_UNSUPPORTED_VERSION : Decision.ACCEPT_UNVERIFIED;
        }

        // A missing or unparseable instant never matches, so it is invalid rather than stale.
        if (!signer.matches(exchange, routingKey, signedAtHeader, body, signatureHeader)) {
            return verifySignatures ? Decision.REJECT_INVALID : Decision.ACCEPT_UNVERIFIED;
        }

        if (!isFresh(signedAtHeader)) {
            return verifySignatures ? Decision.REJECT_STALE : Decision.ACCEPT_UNVERIFIED;
        }
        return Decision.ACCEPT;
    }

    private static boolean isSupportedVersion(String versionHeader) {
        return versionHeader == null || versionHeader.isBlank()
                || DomainEventSigner.SIGNATURE_VERSION.equals(versionHeader.trim());
    }

    private boolean isFresh(String signedAtHeader) {
        long signedAt;
        try {
            signedAt = Long.parseLong(signedAtHeader.trim());
        } catch (NumberFormatException | NullPointerException e) {
            // Unreachable once the signature matched; kept so a refactor cannot crash the consume path.
            return false;
        }
        long skew = Math.abs(clock.millis() - signedAt);
        return skew <= freshnessWindow.toMillis();
    }
}
