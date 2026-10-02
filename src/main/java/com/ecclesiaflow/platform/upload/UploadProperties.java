package com.ecclesiaflow.platform.upload;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Binds {@code ecclesiaflow.platform.upload.*}. The defaults suit a 384 MB, one-CPU
 * container; an instance with more heap and CPUs can allow more decodes at once.
 */
@ConfigurationProperties(prefix = "ecclesiaflow.platform.upload")
public class UploadProperties {

    private final Image image = new Image();

    public Image getImage() {
        return image;
    }

    /** Limits on image decoding, shared by every upload this instance sanitizes. */
    public static class Image {

        /** Image decodes allowed to run at once on this instance. */
        private int maxConcurrentDecodes = ImageSanitizer.DEFAULT_MAX_CONCURRENT_DECODES;

        /** How long an upload waits for a decode slot before it is refused; zero refuses at once. */
        private Duration decodeWait = ImageSanitizer.DEFAULT_DECODE_WAIT;

        public int getMaxConcurrentDecodes() {
            return maxConcurrentDecodes;
        }

        public void setMaxConcurrentDecodes(int maxConcurrentDecodes) {
            this.maxConcurrentDecodes = maxConcurrentDecodes;
        }

        public Duration getDecodeWait() {
            return decodeWait;
        }

        public void setDecodeWait(Duration decodeWait) {
            this.decodeWait = decodeWait;
        }
    }
}
