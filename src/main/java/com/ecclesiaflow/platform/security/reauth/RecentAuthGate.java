package com.ecclesiaflow.platform.security.reauth;

import com.ecclesiaflow.platform.security.reauth.code.ConfirmationCodeSentException;
import com.ecclesiaflow.platform.security.reauth.code.web.EmailCodeConfirmation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The one entry point of a sensitive gesture, for {@link RecentAuthAspect} and for a gesture that needs the check in
 * some of its forms only: the policy decides, then the link of the module's {@link PendingConfirmationIssuer} or
 * the platform's e-mail code holds what does not pass.
 *
 * <p>The collaborators are looked up per call: the issuer belongs to the module's business layer, and the e-mail
 * code exists only where the module enables it. {@link RecentAuthVerifier} has already refused to start a module
 * whose annotated gestures would miss one.</p>
 */
public class RecentAuthGate {

    private static final Duration DEFAULT_MAX_AGE = Duration.ofSeconds(RecentAuthRequired.DEFAULT_MAX_AGE_SECONDS);

    private final ObjectProvider<RecentAuthPolicy> policy;
    private final ObjectProvider<PendingConfirmationIssuer> issuer;
    private final ObjectProvider<EmailCodeConfirmation> emailCodes;

    public RecentAuthGate(ObjectProvider<RecentAuthPolicy> policy, ObjectProvider<PendingConfirmationIssuer> issuer,
                          ObjectProvider<EmailCodeConfirmation> emailCodes) {
        this.policy = policy;
        this.issuer = issuer;
        this.emailCodes = emailCodes;
    }

    /**
     * @param arguments what the gesture received, for the issuer to resume it or for the code to be bound to it
     * @return empty when the gesture runs now; else the body of its 202, the gesture not run
     * @throws ReauthenticationRequiredException  when the user must sign in again first
     * @throws SignInMethodsUnavailableException when the account's sign-in methods cannot be read
     */
    public Optional<Object> hold(String action, Duration maxAge, LinkedAccountProof linkedAccounts,
                                 List<Object> arguments) {
        RecentAuthVerdict verdict = policy.getObject().require(maxAge, linkedAccounts);
        return switch (verdict) {
            case PASSED -> Optional.empty();
            case CONFIRM_OUT_OF_BAND -> Optional.of(linkBody(new SensitiveAction(action, subject(), arguments)));
            case CONFIRM_BY_EMAIL_CODE -> emailCodes(action)
                    .confirm(new SensitiveAction(action, subject(), arguments))
                    .map(Object.class::cast);
        };
    }

    /**
     * For a gesture that needs a recent sign-in in some of its forms only (a downgrade, a payment on the saved card),
     * called before any effect, under {@link LinkedAccountProof#SECOND_FACTOR_OR_EMAIL_CODE} and the default age.
     * Returns when the gesture may run.
     *
     * @param arguments what the module's {@code ConfirmableGestures} binds the code to
     * @throws ConfirmationCodeSentException answered 202: a code went out and the gesture waits for it
     */
    public void require(String action, Object... arguments) {
        List<Object> received = arguments == null ? List.of() : Arrays.asList(arguments);
        hold(action, DEFAULT_MAX_AGE, LinkedAccountProof.SECOND_FACTOR_OR_EMAIL_CODE, received)
                .ifPresent(body -> {
                    throw new ConfirmationCodeSentException((PendingConfirmation) body);
                });
    }

    private Object linkBody(SensitiveAction action) {
        Object body = issuer.getObject().issue(action);
        if (body == null) {
            throw new IllegalStateException("The PendingConfirmationIssuer returned no body for " + action.name());
        }
        return body;
    }

    // Fails closed: a gesture asked for a code where none is configured is a bug, never a pass.
    private EmailCodeConfirmation emailCodes(String action) {
        EmailCodeConfirmation confirmation = emailCodes.getIfAvailable();
        if (confirmation == null) {
            throw new IllegalStateException(action + " confirms linked accounts by e-mail code, which the module "
                    + "does not enable");
        }
        return confirmation;
    }

    // The policy only lets a gesture be held for an authenticated token that names its subject.
    private static String subject() {
        JwtAuthenticationToken token = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        return token.getToken().getSubject();
    }
}
