package com.ecclesiaflow.platform.security.reauth.code;

import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;

/** The service on the in-memory store, whose transactions roll back as the database's do. */
public final class EmailCodeTestSupport {

    private EmailCodeTestSupport() {
    }

    public static EmailCodeService service(InMemoryEmailCodeStore store, EmailCodeSecrets secrets,
                                           EmailCodeMailer mailer, ApplicationEventPublisher events, Clock clock) {
        return new EmailCodeService(store, secrets, mailer, store.transactions, events, clock);
    }
}
