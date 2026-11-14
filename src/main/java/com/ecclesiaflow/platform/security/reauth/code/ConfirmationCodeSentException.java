package com.ecclesiaflow.platform.security.reauth.code;

import com.ecclesiaflow.platform.security.reauth.PendingConfirmation;

import java.util.Objects;

/**
 * Answered 202 with its {@link PendingConfirmation}, never as an error: a gesture checked in the middle of its
 * delegate cannot return the 202 itself, since the generated signature types the body of its success, and the 202
 * must leave before any effect.
 */
public class ConfirmationCodeSentException extends RuntimeException {

    private final transient PendingConfirmation pending;

    public ConfirmationCodeSentException(PendingConfirmation pending) {
        super("A confirmation code was sent; the gesture waits for it", null, false, false);
        this.pending = Objects.requireNonNull(pending, "pending");
    }

    public PendingConfirmation getPending() {
        return pending;
    }
}
