package com.ecclesiaflow.platform.journal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bound to {@code ecclesiaflow.journal.*}. Off by default: a module that does not opt in is untouched. */
@ConfigurationProperties(prefix = "ecclesiaflow.journal")
public class JournalProperties {

    private boolean enabled = false;

    /** The module's name on the metrics and log lines: auth, members, church, chat. No default. */
    private String module;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getModule() {
        return module;
    }

    public void setModule(String module) {
        this.module = module;
    }
}
