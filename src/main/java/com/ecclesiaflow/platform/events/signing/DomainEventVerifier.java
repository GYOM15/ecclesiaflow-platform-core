package com.ecclesiaflow.platform.events.signing;

import java.time.Clock;
import java.time.Duration;

/**
 * Applies the verification policy to an inbound domain event's signature
 * (security findings C07 and F054). Framework-light: operates on the received
 * exchange and routing key, the raw body bytes and the two {@code x-ef-*}
 * header values — no Spring/AMQP types — so it is callable from any consumer
 * wiring.
 *
 * <p>Policy:</p>
 * <ul>
 *   <li><b>Signing disabled</b> (blank secret): always {@link Decision#ACCEPT}
 *       — the fleet-wide migration escape hatch.</li>
 *   <li><b>verify-signatures = false</b> (lenient, default): a valid, fresh
 *       signature is {@link Decision#ACCEPT}; anything else is
 *       {@link Decision#ACCEPT_UNVERIFIED} — the caller accepts the message but
 *       should log a warning. Lets a consumer ship before publishers sign.</li>
 *   <li><b>verify-signatures = true</b> (strict): a valid, fresh signature is
 *       {@link Decision#ACCEPT}; a missing signature is
 *       {@link Decision#REJECT_MISSING}, one that does not match is
 *       {@link Decision#REJECT_INVALID}, and one that matches but was signed
 *       outside the freshness window is {@link Decision#REJECT_STALE}.</li>
 * </ul>
 *
 * <h2>Why the destination is an argument</h2>
 *
 * <p>The signature binds the exchange and routing key (see
 * {@link DomainEventSigner}), so verification must be handed the values the
 * broker actually delivered on — {@code getReceivedExchange()} /
 * {@code getReceivedRoutingKey()} — and <strong>never</strong> a destination
 * read back out of a message header. A header travels with the message and is
 * chosen by whoever published it; checking a signature against a destination the
 * publisher supplied would verify that a forger agreed with themselves.</p>
 */
public class DomainEventVerifier {

    /** Default freshness window: a signature older or newer than this is stale. */
    public static final Duration DEFAULT_FRESHNESS_WINDOW = Duration.ofMinutes(5);

    /** Outcome of a verification, capturing both the accept/reject call and why. */
    public enum Decision {
        /** Signature present, valid and fresh (or signing disabled) — deliver. */
        ACCEPT(true),
        /** Lenient mode, signature missing/invalid/stale — deliver, but log a warning. */
        ACCEPT_UNVERIFIED(true),
        /** Strict mode, no signature header present — drop. */
        REJECT_MISSING(false),
        /** Strict mode, signature present but did not match — drop. */
        REJECT_INVALID(false),
        /**
         * Strict mode, signature matched but its instant is outside the
         * freshness window — a replay of a genuinely signed event, or a clock
         * badly out of step. Drop either way.
         */
        REJECT_STALE(false);

        private final boolean accepted;

        Decision(boolean accepted) {
            this.accepted = accepted;
        }

        /** Whether the message should be delivered to the listener. */
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

    /**
     * @param freshnessWindow how far the signing instant may sit from now, in
     *                        either direction (the fleet's clocks are NTP-synced
     *                        but not identical, so the window is symmetric)
     * @param clock           the clock to compare against; injected for tests
     */
    public DomainEventVerifier(DomainEventSigner signer, boolean verifySignatures,
                               Duration freshnessWindow, Clock clock) {
        this.signer = signer;
        this.verifySignatures = verifySignatures;
        this.freshnessWindow = freshnessWindow;
        this.clock = clock;
    }

    /**
     * Decides whether an inbound message should be delivered.
     *
     * @param exchange        the exchange the message was RECEIVED on
     * @param routingKey      the routing key the message was RECEIVED with
     * @param body            the raw message body bytes
     * @param signatureHeader the {@value DomainEventSigner#SIGNATURE_HEADER}
     *                        value, or {@code null} if the header is absent
     * @param signedAtHeader  the {@value DomainEventSigner#SIGNED_AT_HEADER}
     *                        value, or {@code null} if the header is absent
     * @return the {@link Decision}; consult {@link Decision#isAccepted()}
     */
    public Decision verify(String exchange, String routingKey, byte[] body,
                           String signatureHeader, String signedAtHeader) {
        if (!signer.isEnabled()) {
            return Decision.ACCEPT;
        }

        boolean hasSignature = signatureHeader != null && !signatureHeader.isBlank();
        if (!hasSignature) {
            return verifySignatures ? Decision.REJECT_MISSING : Decision.ACCEPT_UNVERIFIED;
        }

        // A missing or unparseable instant cannot be signed material, so it can
        // never match — treated as an invalid signature rather than a stale one,
        // which is what it is.
        if (!signer.matches(exchange, routingKey, signedAtHeader, body, signatureHeader)) {
            return verifySignatures ? Decision.REJECT_INVALID : Decision.ACCEPT_UNVERIFIED;
        }

        if (!isFresh(signedAtHeader)) {
            return verifySignatures ? Decision.REJECT_STALE : Decision.ACCEPT_UNVERIFIED;
        }
        return Decision.ACCEPT;
    }

    private boolean isFresh(String signedAtHeader) {
        long signedAt;
        try {
            signedAt = Long.parseLong(signedAtHeader.trim());
        } catch (NumberFormatException | NullPointerException e) {
            // Unreachable while the signature matched (the instant is signed
            // material), but a verifier that assumes its inputs are well-formed
            // is one refactor away from a crash on the consume path.
            return false;
        }
        long skew = Math.abs(clock.millis() - signedAt);
        return skew <= freshnessWindow.toMillis();
    }
}
