package com.ecclesiaflow.platform.security.reauth.code;

/** Answered 429 {@code REAUTH_CODE_LOCKED} with {@code Retry-After}: ten wrong codes within 24 hours. */
public class ReauthCodeLockedException extends RuntimeException {

    private final long retryAfterSeconds;

    public ReauthCodeLockedException(long retryAfterSeconds) {
        super("Too many wrong confirmation codes; retry after " + retryAfterSeconds + "s");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
