package com.ecclesiaflow.platform.security.reauth.code;

import com.ecclesiaflow.platform.security.reauth.SensitiveAction;

import java.util.List;
import java.util.Set;

/**
 * What only the module knows of each gesture it confirms by an e-mail code. The {@link SensitiveAction} carries
 * the gesture's name and the arguments its method or its conditional check received.
 */
public interface ConfirmableGestures {

    /**
     * Every gesture name this module confirms by code. The module does not start unless it equals the labels of
     * its contract's operations marked {@code x-ef-reauth-linked: email-code}.
     */
    Set<String> actions();

    /**
     * What the code is bound to beyond the gesture's name, the subject and the {@code Idempotency-Key}: the church
     * the token acts for, then every argument that changes what the gesture does (plan, pack, card, limits). Plain
     * values or ordered lists of them, as {@link com.ecclesiaflow.platform.idempotency.IdempotencyFingerprint}
     * takes them; the same request must give the same parts, and any other request other parts.
     */
    List<Object> boundParts(SensitiveAction action);

    /**
     * Refuses, with the gesture's own exception, what running it now would refuse, by reads only, before any
     * code is sent: nobody types a code to be refused afterwards.
     */
    void precheck(SensitiveAction action);

    /** The closed label the e-mail names the gesture with, such as {@code billing.cancel}. */
    String emailLabel(SensitiveAction action);
}
