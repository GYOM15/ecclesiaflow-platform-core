package com.ecclesiaflow.platform.security.reauth.code;

/** Lifecycle of a challenge; only {@link #PENDING} takes a code, and an account holds one at most. */
public enum EmailCodeStatus {

    /** Waiting for its code; also the state a lapsed challenge keeps until a new gesture replaces it. */
    PENDING,

    /** Its code was accepted, before the gesture ran. A challenge bound to an Idempotency-Key still replays. */
    CONSUMED,

    /** Replaced by another gesture of the account, or presented with another request than its own. */
    SUPERSEDED,

    /** Its code could not be sent: the code's digest is erased, so no guess can ever match it. */
    VOID
}
