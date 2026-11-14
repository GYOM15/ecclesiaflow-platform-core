package com.ecclesiaflow.platform.security.reauth.code.web;

import com.ecclesiaflow.platform.idempotency.IdempotencyKey;
import com.ecclesiaflow.platform.idempotency.web.IdempotencyKeyResolver;
import com.ecclesiaflow.platform.security.reauth.PendingConfirmation;
import com.ecclesiaflow.platform.security.reauth.ReauthenticationRequiredException;
import com.ecclesiaflow.platform.security.reauth.SensitiveAction;
import com.ecclesiaflow.platform.security.reauth.code.ConfirmableGestures;
import com.ecclesiaflow.platform.security.reauth.code.EmailCodeIssue;
import com.ecclesiaflow.platform.security.reauth.code.EmailCodeSecrets;
import com.ecclesiaflow.platform.security.reauth.code.EmailCodeService;
import com.ecclesiaflow.platform.security.reauth.code.InvalidReauthCodeException;
import com.ecclesiaflow.platform.security.reauth.code.ReauthHeaders;
import com.ecclesiaflow.platform.security.reauth.code.ReauthProof;
import com.ecclesiaflow.platform.security.reauth.code.events.EmailCodeEvents;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The request's side of the e-mail code: what it presents in {@code Reauth-Challenge} and {@code Reauth-Code}, the
 * request the code is bound to (the gesture, the {@code sub}, the {@code Idempotency-Key} of an operation that runs
 * the primitive, then the module's bound parts), and the address and locale the token gives.
 */
public class EmailCodeConfirmation {

    static final String EMAIL = "email";
    static final String LOCALE = "locale";

    private final EmailCodeService service;
    private final EmailCodeSecrets secrets;
    private final ConfirmableGestures gestures;
    private final IdempotencyKeyResolver idempotencyKeys;
    private final ApplicationEventPublisher events;

    /** @param idempotencyKeys {@code null} in a module whose contract marks no {@code x-ef-idempotency} */
    public EmailCodeConfirmation(EmailCodeService service, EmailCodeSecrets secrets, ConfirmableGestures gestures,
                                 IdempotencyKeyResolver idempotencyKeys, ApplicationEventPublisher events) {
        this.service = Objects.requireNonNull(service, "service");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.gestures = Objects.requireNonNull(gestures, "gestures");
        this.idempotencyKeys = idempotencyKeys;
        this.events = Objects.requireNonNull(events, "events");
    }

    /** The gestures the module confirms by code, which its contract marks {@code x-ef-reauth-linked}. */
    public Set<String> actions() {
        return Set.copyOf(gestures.actions());
    }

    /**
     * For a linked account without an authenticator app, once its policy asked for a code.
     *
     * @return empty when the request carries the code of the challenge bound to it: the gesture runs; else the 202
     *         body of the code just sent, after the module's read-only checks of the gesture passed
     * @throws InvalidReauthCodeException  on malformed headers, uncounted, or a wrong code
     * @throws IllegalStateException       for a gesture the module does not confirm by code: a bug, never a pass
     */
    public Optional<PendingConfirmation> confirm(SensitiveAction action) {
        Objects.requireNonNull(action, "action");
        if (!gestures.actions().contains(action.name())) {
            throw new IllegalStateException(action.name() + " is not a gesture of the module's ConfirmableGestures");
        }
        HttpServletRequest request = currentRequest();
        Optional<ReauthProof> proof;
        try {
            proof = ReauthHeaders.parse(values(request, ReauthHeaders.CHALLENGE), values(request, ReauthHeaders.CODE));
        } catch (InvalidReauthCodeException malformed) {
            events.publishEvent(new EmailCodeEvents.Malformed(action.subject(), action.name()));
            throw malformed;
        }
        Optional<IdempotencyKey> key = idempotencyKeys == null ? Optional.empty() : idempotencyKeys.resolve(request);
        List<Object> bound = Objects.requireNonNull(gestures.boundParts(action),
                () -> "ConfirmableGestures.boundParts returned nothing for " + action.name());
        String binding = secrets.bindingDigest(action.name(), action.subject(),
                key.map(IdempotencyKey::value).orElse(null), bound);
        if (proof.isPresent()) {
            service.verify(action.subject(), proof.get(), binding);
            return Optional.empty();
        }
        gestures.precheck(action);
        Jwt token = currentToken();
        return Optional.of(service.issue(new EmailCodeIssue(action.subject(), action.name(),
                gestures.emailLabel(action), binding, key.isPresent(), claim(token, EMAIL), claim(token, LOCALE))));
    }

    /**
     * A new code on the caller's open challenge, for the console's "Resend the code".
     *
     * @throws ReauthenticationRequiredException without a token that names an account
     */
    public PendingConfirmation resend(UUID challengeId) {
        Jwt token = currentToken();
        return service.resend(token.getSubject(), challengeId, claim(token, EMAIL), claim(token, LOCALE));
    }

    private static List<String> values(HttpServletRequest request, String header) {
        return request.getHeaders(header) == null ? List.of() : Collections.list(request.getHeaders(header));
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servlet)) {
            throw new IllegalStateException("No servlet request is bound to this thread");
        }
        return servlet.getRequest();
    }

    private static Jwt currentToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token && token.isAuthenticated()
                && StringUtils.hasText(token.getToken().getSubject())) {
            return token.getToken();
        }
        throw ReauthenticationRequiredException.noUserToken();
    }

    // Keycloak is the address's only writer, and changing it is itself a confirmed gesture.
    private static String claim(Jwt token, String name) {
        return token.getClaims().get(name) instanceof String value && StringUtils.hasText(value) ? value : null;
    }
}
