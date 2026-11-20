package com.ecclesiaflow.platform.webhooks.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/** Binds {@code ecclesiaflow.webhooks.body-limit.*}. */
@ConfigurationProperties(prefix = "ecclesiaflow.webhooks.body-limit")
public class WebhookBodyLimitProperties {

    /** Off by default: a module without an anonymous provider route is untouched. */
    private boolean enabled = false;

    /** Paths whose body is bounded, matched by prefix within the application; at least one once enabled. */
    private List<String> pathPrefixes = new ArrayList<>();

    /** 256 KiB: a provider's notification is a few kilobytes, so the bound only meets a forged body. */
    private int maxBytes = 262_144;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getPathPrefixes() {
        return pathPrefixes;
    }

    public void setPathPrefixes(List<String> pathPrefixes) {
        this.pathPrefixes = pathPrefixes;
    }

    public int getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(int maxBytes) {
        this.maxBytes = maxBytes;
    }
}
