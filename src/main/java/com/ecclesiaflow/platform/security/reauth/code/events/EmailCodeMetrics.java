package com.ecclesiaflow.platform.security.reauth.code.events;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.event.EventListener;

import java.util.List;

/**
 * What the e-mail codes come to, per outcome: billing alerts on any code sent (every holder of its right is held
 * to an authenticator app), on failed sends and on locked accounts.
 */
public class EmailCodeMetrics {

    /** Exported to Prometheus as {@code ef_reauth_email_codes_total}. */
    public static final String CODES_METRIC = "ef.reauth.email.codes";

    static final List<String> OUTCOMES = List.of("sent", "not_sent", "verified", "invalid", "expired", "locked",
            "superseded");

    private final MeterRegistry registry;

    /** @param registry may be {@code null}: nothing is then counted. */
    public EmailCodeMetrics(MeterRegistry registry) {
        this.registry = registry;
        if (registry == null) {
            return;
        }
        // Published at zero: increase() cannot see the first increment of a series that starts with it.
        OUTCOMES.forEach(this::counter);
    }

    @EventListener
    public void onSent(EmailCodeEvents.Sent event) {
        count("sent");
    }

    @EventListener
    public void onNotSent(EmailCodeEvents.NotSent event) {
        count("not_sent");
    }

    @EventListener
    public void onVerified(EmailCodeEvents.Verified event) {
        count("verified");
    }

    @EventListener
    public void onInvalid(EmailCodeEvents.Invalid event) {
        count("invalid");
    }

    @EventListener
    public void onMalformed(EmailCodeEvents.Malformed event) {
        count("invalid");
    }

    @EventListener
    public void onExpired(EmailCodeEvents.Expired event) {
        count("expired");
    }

    @EventListener
    public void onLocked(EmailCodeEvents.Locked event) {
        count("locked");
    }

    @EventListener
    public void onSuperseded(EmailCodeEvents.Superseded event) {
        count("superseded");
    }

    private void count(String outcome) {
        if (registry != null) {
            counter(outcome).increment();
        }
    }

    private Counter counter(String outcome) {
        return Counter.builder(CODES_METRIC)
                .description("E-mail codes confirming the sensitive gestures of linked accounts, by outcome")
                .tag("outcome", outcome)
                .register(registry);
    }
}
