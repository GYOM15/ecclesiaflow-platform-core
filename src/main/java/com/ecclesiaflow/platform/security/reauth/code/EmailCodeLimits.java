package com.ecclesiaflow.platform.security.reauth.code;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The closed durations and caps of the e-mail code (PLAN-REAUTH-CODE §4.4): no property moves them, so no
 * deployment can loosen one. The counts are kept per account and nothing resets them, a success, a new code, a
 * new gesture or a new key included (NIST SP 800-63B-4).
 */
public final class EmailCodeLimits {

    /** NIST SP 800-63B-4, the in-app signup's value. */
    public static final Duration CODE_LIFETIME = Duration.ofMinutes(10);
    /** New codes come without redoing the gesture until then; past it, the gesture starts again. */
    public static final Duration CHALLENGE_LIFETIME = Duration.ofMinutes(30);
    public static final Duration RESEND_DELAY = Duration.ofSeconds(60);
    public static final int FAILURES_PER_CODE = 5;
    public static final int FAILURES_PER_ACCOUNT = 10;
    public static final Duration FAILURE_WINDOW = Duration.ofHours(24);
    /** Resends included: the address receives ten mails a day at most. */
    public static final int CODES_PER_HOUR = 3;
    public static final int CODES_PER_DAY = 10;
    public static final Duration HOUR = Duration.ofHours(1);
    public static final Duration DAY = Duration.ofDays(1);
    /** Rows keep only digests and the subject; seven days cover every window and an investigation. */
    public static final Duration RETENTION = Duration.ofDays(7);

    private EmailCodeLimits() {
    }

    /** Empty while one more is allowed; otherwise how long until the oldest occurrence leaves the window. */
    public static Optional<Duration> waitBeforeNext(EmailCodeStore.CounterWindow window, int limit, Duration span,
                                                    Instant now) {
        if (window.count() < limit) {
            return Optional.empty();
        }
        Duration wait = window.oldest() == null ? span : Duration.between(now, window.oldest().plus(span));
        return Optional.of(wait.compareTo(Duration.ofSeconds(1)) < 0 ? Duration.ofSeconds(1) : wait);
    }

    /** Whole seconds, rounded up, for {@code Retry-After}. */
    public static long retryAfterSeconds(Duration wait) {
        long seconds = wait.toSeconds();
        return wait.minusSeconds(seconds).isZero() ? Math.max(1, seconds) : seconds + 1;
    }
}
