package com.ecclesiaflow.platform.events.signing;

/**
 * Applies the verification policy to an inbound domain event's signature
 * (security finding C07). Framework-light: operates on the routing key, raw
 * body bytes, and the {@value DomainEventSigner#SIGNATURE_HEADER} header value
 * — no Spring/AMQP types — so it is callable from any consumer wiring.
 *
 * <p>Policy:</p>
 * <ul>
 *   <li><b>Signing disabled</b> (blank secret): always {@link Decision#ACCEPT}
 *       — the fleet-wide migration escape hatch.</li>
 *   <li><b>verify-signatures = false</b> (lenient, default): a valid signature
 *       is {@link Decision#ACCEPT}; a missing or invalid one is
 *       {@link Decision#ACCEPT_UNVERIFIED} — the caller accepts the message but
 *       should log a warning. Lets a consumer ship before publishers sign.</li>
 *   <li><b>verify-signatures = true</b> (strict): a valid signature is
 *       {@link Decision#ACCEPT}; a missing signature is {@link Decision#REJECT_MISSING}
 *       and an invalid one is {@link Decision#REJECT_INVALID}.</li>
 * </ul>
 */
public class DomainEventVerifier {

    /** Outcome of a verification, capturing both the accept/reject call and why. */
    public enum Decision {
        /** Signature present and valid (or signing disabled) — deliver. */
        ACCEPT(true),
        /** Lenient mode, signature missing/invalid — deliver, but log a warning. */
        ACCEPT_UNVERIFIED(true),
        /** Strict mode, no signature header present — drop. */
        REJECT_MISSING(false),
        /** Strict mode, signature present but did not match — drop. */
        REJECT_INVALID(false);

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

    public DomainEventVerifier(DomainEventSigner signer, boolean verifySignatures) {
        this.signer = signer;
        this.verifySignatures = verifySignatures;
    }

    /**
     * Decides whether an inbound message should be delivered.
     *
     * @param body            the raw message body bytes
     * @param signatureHeader the {@value DomainEventSigner#SIGNATURE_HEADER}
     *                        value, or {@code null} if the header is absent
     * @return the {@link Decision}; consult {@link Decision#isAccepted()}
     */
    public Decision verify(byte[] body, String signatureHeader) {
        if (!signer.isEnabled()) {
            return Decision.ACCEPT;
        }

        boolean hasSignature = signatureHeader != null && !signatureHeader.isBlank();
        if (!hasSignature) {
            return verifySignatures ? Decision.REJECT_MISSING : Decision.ACCEPT_UNVERIFIED;
        }

        if (signer.matches(body, signatureHeader)) {
            return Decision.ACCEPT;
        }
        return verifySignatures ? Decision.REJECT_INVALID : Decision.ACCEPT_UNVERIFIED;
    }
}
