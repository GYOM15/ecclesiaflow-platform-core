package com.ecclesiaflow.platform.security.reauth;

/** The outcomes that let a gesture go on; every refusal is an exception. */
public enum RecentAuthVerdict {

    /** The sign-in is recent and was not brokered, or a recent second factor is attested: the gesture runs now. */
    PASSED,

    /** The account is linked to a provider: the gesture waits for a link sent to its current address. */
    CONFIRM_OUT_OF_BAND,

    /**
     * The account is linked to a provider and has no authenticator app known: the gesture runs once the request
     * carries the code sent to its address. Only {@link LinkedAccountProof#SECOND_FACTOR_OR_EMAIL_CODE} answers it.
     */
    CONFIRM_BY_EMAIL_CODE
}
