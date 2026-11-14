package com.ecclesiaflow.platform.security.reauth.code;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The e-mail code of a module whose linked accounts confirm their gestures by code. Only the switch and the key
 * are settings: the durations and caps are closed ({@link EmailCodeLimits}).
 */
@ConfigurationProperties(prefix = EmailCodeProperties.PREFIX)
public class EmailCodeProperties {

    public static final String PREFIX = "ecclesiaflow.security.reauth.email-code";

    /** Off by default: members and church keep their link and gain nothing. */
    private boolean enabled;

    /**
     * 32 random bytes or more, base64, the module's own (billing: {@code BILLING_REAUTH_CODE_KEY}): a leak is worth
     * that module only. Changing it voids the challenges in flight, 30 minutes at most; the counts stay.
     */
    private String key;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    @Override
    public String toString() {
        return "EmailCodeProperties[enabled=" + enabled + "]";
    }
}
