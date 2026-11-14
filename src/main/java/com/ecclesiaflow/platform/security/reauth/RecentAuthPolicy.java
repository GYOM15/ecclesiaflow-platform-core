package com.ecclesiaflow.platform.security.reauth;

import com.ecclesiaflow.platform.logging.SecurityMaskingUtils;
import com.ecclesiaflow.platform.security.reauth.events.RecentAuthEvents;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Decides whether a sensitive gesture may run now, for any token, web or mobile: a stolen session must not be
 * able to close an account or move it to another address. {@link RecentAuthRequired} applies it to a method; a
 * module calls it directly where the gesture is conditional (closing one's own account through its id).
 *
 * <p>The account decides, not the session: one linked to an identity provider is confirmed out of band, because
 * the provider signs the user back in silently and Google ignores {@code prompt=login}, so neither
 * {@code auth_time} nor the absence of an {@code identity_provider} note proves anything for it. Under
 * {@link LinkedAccountProof#SECOND_FACTOR_OR_EMAIL_CODE} it passes instead on a second factor Keycloak attests,
 * which the provider cannot present for it.</p>
 */
public class RecentAuthPolicy {

    static final String AUTH_TIME = "auth_time";
    static final String IDENTITY_PROVIDER = "identity_provider";
    static final String AMR = "amr";
    // The references the realm's flows write for the authenticator app and for a recovery code.
    static final Set<String> SECOND_FACTORS = Set.of("otp", "recovery-code");

    private final SignInMethodsClient signInMethods;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public RecentAuthPolicy(SignInMethodsClient signInMethods, Clock clock, ApplicationEventPublisher events) {
        this.signInMethods = Objects.requireNonNull(signInMethods, "signInMethods");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.events = Objects.requireNonNull(events, "events");
    }

    /** {@link #require(Authentication, Duration)} for the request being served. */
    public RecentAuthVerdict require(Duration maxAge) {
        return require(maxAge, LinkedAccountProof.CONFIRMATION_LINK);
    }

    /** {@link #require(Authentication, Duration, LinkedAccountProof)} for the request being served. */
    public RecentAuthVerdict require(Duration maxAge, LinkedAccountProof linkedAccounts) {
        return require(SecurityContextHolder.getContext().getAuthentication(), maxAge, linkedAccounts);
    }

    /**
     * @return {@link RecentAuthVerdict#PASSED}, or {@link RecentAuthVerdict#CONFIRM_OUT_OF_BAND} for an account
     *         linked to a provider, whatever its token says
     * @throws ReauthenticationRequiredException  when the sign-in is too old, brokered, undated, or there is none
     * @throws SignInMethodsUnavailableException when the account's sign-in methods cannot be read
     */
    public RecentAuthVerdict require(Authentication authentication, Duration maxAge) {
        return require(authentication, maxAge, LinkedAccountProof.CONFIRMATION_LINK);
    }

    /**
     * {@link #require(Authentication, Duration)}, with an account linked to a provider proved as
     * {@code linkedAccounts} says.
     *
     * @return under {@link LinkedAccountProof#SECOND_FACTOR_OR_EMAIL_CODE}, for a linked account,
     *         {@link RecentAuthVerdict#PASSED} on a recent attested second factor, else
     *         {@link RecentAuthVerdict#CONFIRM_BY_EMAIL_CODE} when no authenticator app is known
     * @throws ReauthenticationRequiredException also for a linked account with an authenticator app and no recent
     *                                           attested second factor
     */
    public RecentAuthVerdict require(Authentication authentication, Duration maxAge,
                                     LinkedAccountProof linkedAccounts) {
        if (maxAge == null || maxAge.isNegative() || maxAge.isZero()) {
            throw new IllegalArgumentException("The maximum sign-in age must be positive, was " + maxAge);
        }
        Objects.requireNonNull(linkedAccounts, "linkedAccounts");
        Jwt token = userToken(authentication);
        if (token == null) {
            throw refuse(ReauthenticationRequiredException.noUserToken(), SecurityMaskingUtils.maskId(null), null,
                    maxAge);
        }
        String subject = SecurityMaskingUtils.maskId(token.getSubject());

        SignInMethods methods = signInMethodsOf(token.getSubject(), subject);
        // auth_time has no fraction of a second, so the limit itself still passes.
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Optional<Duration> age = signedInAt(token).map(signedInAt -> Duration.between(signedInAt, now));
        Long ageSeconds = age.map(Duration::toSeconds).orElse(null);
        if (methods.linkedToProvider()) {
            if (linkedAccounts == LinkedAccountProof.SECOND_FACTOR_OR_EMAIL_CODE) {
                return secondFactorOrEmailCode(token, methods, subject, age, maxAge);
            }
            events.publishEvent(new RecentAuthEvents.ConfirmationRequired(subject, methods.linkedProviders()));
            return RecentAuthVerdict.CONFIRM_OUT_OF_BAND;
        }

        if (token.getClaims().get(IDENTITY_PROVIDER) != null) {
            throw refuse(ReauthenticationRequiredException.brokeredSignIn(), subject, ageSeconds, maxAge);
        }
        if (age.isEmpty()) {
            throw refuse(ReauthenticationRequiredException.noSignInTime(), subject, null, maxAge);
        }
        if (age.get().compareTo(maxAge) > 0) {
            throw refuse(ReauthenticationRequiredException.signInTooOld(), subject, ageSeconds, maxAge);
        }
        events.publishEvent(new RecentAuthEvents.Passed(subject, ageSeconds, maxAge.toSeconds()));
        return RecentAuthVerdict.PASSED;
    }

    // The identity_provider note is no refusal here: the flow after the provider asked for the second factor.
    private RecentAuthVerdict secondFactorOrEmailCode(Jwt token, SignInMethods methods, String subject,
                                                      Optional<Duration> age, Duration maxAge) {
        Long ageSeconds = age.map(Duration::toSeconds).orElse(null);
        if (age.isPresent() && age.get().compareTo(maxAge) <= 0 && attestsSecondFactor(token)) {
            events.publishEvent(new RecentAuthEvents.SecondFactorPassed(subject, ageSeconds, maxAge.toSeconds()));
            return RecentAuthVerdict.PASSED;
        }
        if (methods.knownToHaveAuthenticator()) {
            throw refuse(ReauthenticationRequiredException.secondFactorNotPresented(), subject, ageSeconds, maxAge);
        }
        events.publishEvent(new RecentAuthEvents.EmailCodeRequired(subject, methods.linkedProviders(),
                methods.hasAuthenticator() == null));
        return RecentAuthVerdict.CONFIRM_BY_EMAIL_CODE;
    }

    // Keycloak writes a list; a lone string is read as a list of one, anything else attests nothing.
    private static boolean attestsSecondFactor(Jwt token) {
        Object amr = token.getClaims().get(AMR);
        if (amr instanceof String single) {
            return SECOND_FACTORS.contains(single);
        }
        return amr instanceof Collection<?> methods
                && methods.stream().anyMatch(method -> method instanceof String name && SECOND_FACTORS.contains(name));
    }

    private static Jwt userToken(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken token && token.isAuthenticated()
                && StringUtils.hasText(token.getToken().getSubject())) {
            return token.getToken();
        }
        return null;
    }

    // Any failure, a bug in the adapter included, refuses the gesture: it must never let it through.
    private SignInMethods signInMethodsOf(String keycloakUserId, String subject) {
        SignInMethods methods;
        try {
            methods = signInMethods.find(keycloakUserId);
        } catch (RuntimeException failure) {
            events.publishEvent(new RecentAuthEvents.SignInMethodsUnavailable(subject, failure));
            throw new SignInMethodsUnavailableException(failure);
        }
        if (methods == null) {
            IllegalStateException noAnswer = new IllegalStateException("The sign-in methods client returned nothing");
            events.publishEvent(new RecentAuthEvents.SignInMethodsUnavailable(subject, noAnswer));
            throw new SignInMethodsUnavailableException(noAnswer);
        }
        return methods;
    }

    // Keycloak writes seconds; the decoder leaves the claim a number unless a converter made it an Instant. A
    // value no Instant can hold is no sign-in time.
    private static Optional<Instant> signedInAt(Jwt token) {
        Object authTime = token.getClaims().get(AUTH_TIME);
        if (authTime instanceof Instant instant) {
            return Optional.of(instant);
        }
        if (!(authTime instanceof Number number)) {
            return Optional.empty();
        }
        long seconds = number.longValue();
        return seconds < Instant.MIN.getEpochSecond() || seconds > Instant.MAX.getEpochSecond()
                ? Optional.empty()
                : Optional.of(Instant.ofEpochSecond(seconds));
    }

    private ReauthenticationRequiredException refuse(ReauthenticationRequiredException refusal, String subject,
                                                     Long age, Duration maxAge) {
        events.publishEvent(new RecentAuthEvents.Refused(subject, refusal.getReason(), age, maxAge.toSeconds()));
        return refusal;
    }
}
