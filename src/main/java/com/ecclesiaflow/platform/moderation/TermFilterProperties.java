package com.ecclesiaflow.platform.moderation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound to {@code ecclesiaflow.moderation.*}. {@code MODERATION_TERMS_FILE} does not bind here on its own: a
 * module bridges it with {@code ecclesiaflow.moderation.terms-file=${MODERATION_TERMS_FILE:}}.
 */
@ConfigurationProperties(prefix = TermFilterProperties.PREFIX)
public class TermFilterProperties {

    public static final String PREFIX = "ecclesiaflow.moderation";

    public static final String TERMS_FILE = PREFIX + ".terms-file";

    /** Path of the UTF-8 term list. A module that sets the property and leaves it blank does not start. */
    private String termsFile;

    public String getTermsFile() {
        return termsFile;
    }

    public void setTermsFile(String termsFile) {
        this.termsFile = termsFile;
    }
}
