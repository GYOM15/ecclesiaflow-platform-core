package com.ecclesiaflow.platform.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The {@code s3} section is fed from the same {@code R2_*} secrets as the Next.js app, so one set of R2
 * credentials serves both.
 */
@ConfigurationProperties(prefix = "ecclesiaflow.object-storage")
public class ObjectStorageProperties {

    public enum Provider {
        /** Local disk, for dev and tests. */
        FILESYSTEM,
        S3
    }

    /** Unset means no {@link ObjectStorage} adapter is created. */
    private Provider provider;

    private final Filesystem filesystem = new Filesystem();

    private final S3 s3 = new S3();

    public Provider getProvider() {
        return provider;
    }

    public void setProvider(Provider provider) {
        this.provider = provider;
    }

    public Filesystem getFilesystem() {
        return filesystem;
    }

    public S3 getS3() {
        return s3;
    }

    public static class Filesystem {

        /** Blank means {@code <java.io.tmpdir>/ecclesiaflow-object-storage}, which the {@code prod} profile refuses. */
        private String basePath = "";

        public String getBasePath() {
            return basePath;
        }

        public void setBasePath(String basePath) {
            this.basePath = basePath;
        }
    }

    public static class S3 {

        /** S3 API endpoint (for R2: {@code https://<account>.r2.cloudflarestorage.com}). */
        private String endpoint;

        private String accessKeyId;

        private String secretAccessKey;

        /** Target bucket (kept private; a CDN sits in front for public reads). */
        private String bucket;

        /** SDK region; R2 ignores it but requires a value — {@code auto}. */
        private String region = "auto";

        /** R2 only supports path-style addressing; keep {@code true} for R2. */
        private boolean pathStyleAccess = true;

        /** CDN or custom domain in front of the private bucket, for public assets only. */
        private String publicBaseUrl;

        /**
         * Objects under these prefixes hold personal data: written {@code Cache-Control: private, no-store} so no
         * CDN keeps a copy past their deletion, and never given a public URL. Setting it replaces the default.
         */
        private Set<String> privateKeyPrefixes = new LinkedHashSet<>(List.of("member-photos"));

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getAccessKeyId() {
            return accessKeyId;
        }

        public void setAccessKeyId(String accessKeyId) {
            this.accessKeyId = accessKeyId;
        }

        public String getSecretAccessKey() {
            return secretAccessKey;
        }

        public void setSecretAccessKey(String secretAccessKey) {
            this.secretAccessKey = secretAccessKey;
        }

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public boolean isPathStyleAccess() {
            return pathStyleAccess;
        }

        public void setPathStyleAccess(boolean pathStyleAccess) {
            this.pathStyleAccess = pathStyleAccess;
        }

        public String getPublicBaseUrl() {
            return publicBaseUrl;
        }

        public void setPublicBaseUrl(String publicBaseUrl) {
            this.publicBaseUrl = publicBaseUrl;
        }

        public Set<String> getPrivateKeyPrefixes() {
            return privateKeyPrefixes;
        }

        public void setPrivateKeyPrefixes(Set<String> privateKeyPrefixes) {
            this.privateKeyPrefixes = privateKeyPrefixes;
        }
    }
}
