package com.ecclesiaflow.platform.security.reauth.code.events;

import com.ecclesiaflow.platform.logging.SecurityMaskingUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;

/**
 * Every line names the account by its masked {@code sub} and the challenge by its masked id, never a code, an
 * address, a digest or a {@code Reauth-*} header. A lock and a failed send are WARN: the first is an attack or a
 * lost user, the second leaves every linked account without its confirmation.
 */
@Slf4j
public class EmailCodeEventListener {

    @EventListener
    public void onSent(EmailCodeEvents.Sent event) {
        log.info("REAUTH-CODE: ✅ {} code sent for {} (challenge={}, subject={})", event.renewed() ? "New" : "First",
                event.action(), SecurityMaskingUtils.maskId(event.challengeId()),
                SecurityMaskingUtils.maskId(event.subject()));
    }

    @EventListener
    public void onReused(EmailCodeEvents.Reused event) {
        log.debug("REAUTH-CODE: Same gesture while its code is valid, nothing sent for {} (challenge={}, subject={})",
                event.action(), SecurityMaskingUtils.maskId(event.challengeId()),
                SecurityMaskingUtils.maskId(event.subject()));
    }

    @EventListener
    public void onNotSent(EmailCodeEvents.NotSent event) {
        if (event.cause() == null) {
            log.warn("REAUTH-CODE: ❌ Code not sent for {}, {} — answered 503 (subject={})", event.action(),
                    event.reason(), SecurityMaskingUtils.maskId(event.subject()));
            return;
        }
        log.warn("REAUTH-CODE: ❌ Code not sent for {}, {} — {}: {}, answered 503 (subject={})", event.action(),
                event.reason(), event.cause().getClass().getSimpleName(),
                SecurityMaskingUtils.escapeControlChars(SecurityMaskingUtils.rootMessage(event.cause())),
                SecurityMaskingUtils.maskId(event.subject()));
    }

    @EventListener
    public void onVerified(EmailCodeEvents.Verified event) {
        log.info("REAUTH-CODE: ✅ Code accepted{} for {} (challenge={}, subject={})",
                event.replayed() ? " again, same request and key" : "", event.action(),
                SecurityMaskingUtils.maskId(event.challengeId()), SecurityMaskingUtils.maskId(event.subject()));
    }

    @EventListener
    public void onInvalid(EmailCodeEvents.Invalid event) {
        log.info("REAUTH-CODE: ❌ Wrong code — {} on this code, {} on the account in 24 h (challenge={}, subject={})",
                event.codeFailures(), event.accountFailures(), SecurityMaskingUtils.maskId(event.challengeId()),
                SecurityMaskingUtils.maskId(event.subject()));
    }

    @EventListener
    public void onMalformed(EmailCodeEvents.Malformed event) {
        log.info("REAUTH-CODE: ❌ Malformed confirmation headers on {}, not counted (subject={})", event.action(),
                SecurityMaskingUtils.maskId(event.subject()));
    }

    @EventListener
    public void onExpired(EmailCodeEvents.Expired event) {
        log.info("REAUTH-CODE: ❌ Code no longer valid ({}) (challenge={}, subject={})", event.reason(),
                SecurityMaskingUtils.maskId(event.challengeId()), SecurityMaskingUtils.maskId(event.subject()));
    }

    @EventListener
    public void onLocked(EmailCodeEvents.Locked event) {
        log.warn("REAUTH-CODE: ⚠ Account locked out of codes for {} s after ten wrong codes (subject={})",
                event.retryAfterSeconds(), SecurityMaskingUtils.maskId(event.subject()));
    }

    @EventListener
    public void onSuperseded(EmailCodeEvents.Superseded event) {
        log.info("REAUTH-CODE: Challenge replaced ({}) (challenge={}, subject={})", event.by(),
                SecurityMaskingUtils.maskId(event.challengeId()), SecurityMaskingUtils.maskId(event.subject()));
    }

    @EventListener
    public void onSendThrottled(EmailCodeEvents.SendThrottled event) {
        log.info("REAUTH-CODE: ⏳ No new code before {} s ({}) (subject={})", event.retryAfterSeconds(), event.rule(),
                SecurityMaskingUtils.maskId(event.subject()));
    }
}
