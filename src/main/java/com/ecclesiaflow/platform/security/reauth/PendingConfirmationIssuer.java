package com.ecclesiaflow.platform.security.reauth;

/**
 * Holds a {@link RecentAuthRequired} gesture of an account linked to an identity provider until it is confirmed
 * from the account's current address, implemented by the module that owns the gesture. Called instead of the
 * annotated method, after the module's other checks on it.
 *
 * <p>Returns the body of the 202: {@link PendingConfirmation} where the operation declares it, or the
 * operation's own 202 schema where it already has one. A failure to send the link throws, so the gesture is
 * refused rather than reported as pending.</p>
 */
public interface PendingConfirmationIssuer {

    Object issue(SensitiveAction action);
}
