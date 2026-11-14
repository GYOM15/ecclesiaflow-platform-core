package com.ecclesiaflow.platform.security.reauth.code;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The two headers a request replays its gesture with, once a code went out. Frozen with the contract: the console
 * sends them as written here, and never logs them.
 */
public final class ReauthHeaders {

    /** The {@code challengeId} of the 202, a canonical UUID. */
    public static final String CHALLENGE = "Reauth-Challenge";

    /** The six digits the mail gave. */
    public static final String CODE = "Reauth-Code";

    /** The contract's {@code pattern} for {@link #CODE}. */
    public static final String CODE_PATTERN = "^[0-9]{6}$";

    // UUID.fromString also takes short groups such as 1-2-3-4-5, which the contract's format: uuid does not.
    private static final Pattern CANONICAL_UUID =
            Pattern.compile("\\p{XDigit}{8}-\\p{XDigit}{4}-\\p{XDigit}{4}-\\p{XDigit}{4}-\\p{XDigit}{12}");
    private static final Pattern SIX_DIGITS = Pattern.compile(CODE_PATTERN);

    private ReauthHeaders() {
    }

    /**
     * @param challenges every value of {@link #CHALLENGE} the request carries
     * @param codes      every value of {@link #CODE}
     * @return empty when it carries neither: the gesture asks for a code
     * @throws InvalidReauthCodeException when one comes without the other, twice, or malformed: not a try
     */
    public static Optional<ReauthProof> parse(List<String> challenges, List<String> codes) {
        if (challenges.isEmpty() && codes.isEmpty()) {
            return Optional.empty();
        }
        if (challenges.size() != 1 || codes.size() != 1) {
            throw InvalidReauthCodeException.malformedProof();
        }
        String challenge = challenges.get(0) == null ? "" : challenges.get(0).strip();
        String code = codes.get(0) == null ? "" : codes.get(0).strip();
        if (!CANONICAL_UUID.matcher(challenge).matches() || !SIX_DIGITS.matcher(code).matches()) {
            throw InvalidReauthCodeException.malformedProof();
        }
        return Optional.of(new ReauthProof(UUID.fromString(challenge), code));
    }
}
