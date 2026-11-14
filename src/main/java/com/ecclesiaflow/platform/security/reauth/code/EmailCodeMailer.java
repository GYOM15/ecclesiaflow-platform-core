package com.ecclesiaflow.platform.security.reauth.code;

/**
 * Sends a code to the account's address, implemented by the module (billing: communication's
 * {@code EmailService.SendEmail}, template {@code EMAIL_TEMPLATE_SENSITIVE_ACTION_CODE}).
 *
 * <p>Returns once the mail is accepted for delivery, within a deadline the adapter sets (3 s); throws otherwise.
 * The challenge is then voided and the gesture answered 503: a code that did not leave never passes for sent.
 * The adapter's logging aspect logs the outcome only, never the code or the address.</p>
 */
public interface EmailCodeMailer {

    void send(EmailCodeDelivery delivery);
}
