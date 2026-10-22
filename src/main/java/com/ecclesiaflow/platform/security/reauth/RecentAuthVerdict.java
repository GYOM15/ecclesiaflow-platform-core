package com.ecclesiaflow.platform.security.reauth;

/** The two outcomes that let a gesture go on; every refusal is an exception. */
public enum RecentAuthVerdict {

    /** The sign-in is recent and was not brokered: the gesture runs now. */
    PASSED,

    /** The account is linked to a provider: the gesture waits for a link sent to its current address. */
    CONFIRM_OUT_OF_BAND
}
