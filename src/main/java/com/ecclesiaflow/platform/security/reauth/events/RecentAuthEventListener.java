package com.ecclesiaflow.platform.security.reauth.events;

import com.ecclesiaflow.platform.logging.SecurityMaskingUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;

import java.util.stream.Collectors;

/**
 * A refusal or a confirmation sent is INFO: support reads why a member was asked to sign in again. A failure to
 * read the sign-in methods is a WARN: every sensitive gesture is refused until auth answers again.
 */
@Slf4j
public class RecentAuthEventListener {

    @EventListener
    public void onPassed(RecentAuthEvents.Passed event) {
        log.debug("REAUTH: Recent sign-in accepted — {} s old, limit {} s, subject={}",
                event.signInAgeSeconds(), event.maxAgeSeconds(), event.subject());
    }

    @EventListener
    public void onRefused(RecentAuthEvents.Refused event) {
        String age = event.signInAgeSeconds() == null ? "unknown" : event.signInAgeSeconds() + " s";
        log.info("REAUTH: ❌ Sign-in required again ({}) — sign-in {}, limit {} s, subject={}",
                event.reason(), age, event.maxAgeSeconds(), event.subject());
    }

    @EventListener
    public void onConfirmationRequired(RecentAuthEvents.ConfirmationRequired event) {
        String providers = event.linkedProviders().stream()
                .map(SecurityMaskingUtils::escapeControlChars)
                .collect(Collectors.joining(", "));
        log.info("REAUTH: ⚠ Account linked to {} — gesture held for a confirmation link, subject={}",
                providers, event.subject());
    }

    @EventListener
    public void onSignInMethodsUnavailable(RecentAuthEvents.SignInMethodsUnavailable event) {
        log.warn("REAUTH: ❌ Sign-in methods unreadable, gesture refused (503) — {}: {}, subject={}",
                event.cause().getClass().getSimpleName(), SecurityMaskingUtils.rootMessage(event.cause()),
                event.subject());
    }
}
