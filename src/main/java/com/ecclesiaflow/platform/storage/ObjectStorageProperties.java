package com.ecclesiaflow.platform.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code ecclesiaflow.object-storage.*}. A single {@link #provider} knob
 * selects the active {@link ObjectStorage} adapter; the {@code filesystem} and
 * {@code s3} sub-sections carry each backend's settings.
 *
 * <p>Deploy note: the {@code s3} section is designed to be fed from the same
 * {@code R2_*} secrets the Next.js app already uses, e.g. in a module's
 * {@code application.yml}:
 * <pre>
 * ecclesiaflow:
 *   object-storage:
 *     provider: ${OBJECT_STORAGE_PROVIDER:filesystem}
 *     s3:
 *       endpoint: ${R2_ENDPOINT:}
 *       access-key-id: ${R2_ACCESS_KEY_ID:}
 *       secret-access-key: ${R2_SECRET_ACCESS_KEY:}
 *       bucket: ${R2_BUCKET:}
 *       public-base-url: ${R2_PUBLIC_BASE_URL:}
 * </pre>
 * so one set of R2 credentials serves both the frontend and the backend.</p>
 */
@ConfigurationProperties(prefix = "ecclesiaflow.object-storage")
public class ObjectStorageProperties {

    /** Which adapter backs {@link ObjectStorage}. */
    public enum Provider {
        /** Local disk — the default for dev/tests; no external service. */
        FILESYSTEM,
        /** S3-compatible object store (Cloudflare R2, AWS S3, MinIO). */
        S3
    }

    /** Active provider; defaults to {@link Provider#FILESYSTEM} when unset. */
    private Provider provider = Provider.FILESYSTEM;

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

    /** Settings for the local-disk adapter. */
    public static class Filesystem {

        /**
         * Root directory objects are written under. Blank resolves to
         * {@code <java.io.tmpdir>/ecclesiaflow-object-storage} (a sane dev
         * default); set explicitly for any persistent use.
         */
        private String basePath = "";

        public String getBasePath() {
            return basePath;
        }

        public void setBasePath(String basePath) {
            this.basePath = basePath;
        }
    }

    /** Settings for the S3-compatible adapter (Cloudflare R2 by default). */
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

        /**
         * Public CDN/custom-domain base in front of the (private) bucket, used to
         * build servable URLs for PUBLIC assets. Optional: reads that must stay
         * access-controlled are proxied through the backend and never use this.
         */
        private String publicBaseUrl;

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
    }
}
