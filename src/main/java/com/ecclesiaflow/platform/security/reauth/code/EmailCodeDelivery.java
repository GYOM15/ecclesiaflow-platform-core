package com.ecclesiaflow.platform.security.reauth.code;

import java.time.Duration;
import java.util.Objects;

/**
 * One code to mail. It lives in memory only, from its draw to this call.
 *
 * @param gesture  the closed label of the gesture, from {@link ConfirmableGestures#emailLabel}
 * @param lifetime how long the code is valid from now, ten minutes at most
 * @param locale   the token's {@code locale} claim, {@code null} for the template's default
 */
public record EmailCodeDelivery(String address, String code, String gesture, Duration lifetime, String locale) {

    public EmailCodeDelivery {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(gesture, "gesture");
        Objects.requireNonNull(lifetime, "lifetime");
    }

    // Neither the code nor the address may reach a log through a toString.
    @Override
    public String toString() {
        return "EmailCodeDelivery[gesture=" + gesture + ", lifetime=" + lifetime + ", locale=" + locale + "]";
    }
}
