package com.ecclesiaflow.platform.security.reauth.code;

/** What the per-account caps count, kept apart from the challenges so that nothing about one resets them. */
public enum EmailCodeOccurrence {

    /** A code handed to the mailer; withdrawn when the mailer refused it, so a failed send costs nothing. */
    SENT,

    /** A wrong code, on any challenge of the account. */
    FAILED
}
