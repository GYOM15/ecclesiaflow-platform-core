package com.ecclesiaflow.platform.storage.s3;

import com.ecclesiaflow.platform.storage.ObjectStorage;
import com.ecclesiaflow.platform.storage.ObjectStorageException;
import com.ecclesiaflow.platform.storage.ObjectStorageProperties;
import com.ecclesiaflow.platform.storage.StorageKeys;
import com.ecclesiaflow.platform.storage.StoredObject;
import com.ecclesiaflow.platform.storage.StoredObjectRef;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * S3-compatible {@link ObjectStorage} adapter — the production backend, pointed
 * at Cloudflare R2 by default (any S3 API works: AWS S3, MinIO).
 *
 * <p>Mirrors the proven frontend R2 client ({@code lib/storage/r2.ts}):
 * path-style addressing, {@code region=auto}, random content-addressed keys, and
 * a one-year immutable cache header (safe because keys never change). Objects
 * under a configured private prefix (member photos by default) get
 * {@code private, no-store} instead and no public URL. The bucket stays PRIVATE;
 * public assets are served through a CDN base URL, access-controlled assets are
 * proxied back through the backend via {@link #get}.</p>
 *
 * <p>Implements {@link AutoCloseable} so Spring closes the underlying
 * {@link S3Client} (and its connection pool) on context shutdown.</p>
 */
public class S3ObjectStorage implements ObjectStorage, AutoCloseable {

    /** One year, immutable — keys are unique per upload so content never changes. */
    private static final String IMMUTABLE_CACHE_CONTROL = "public, max-age=31536000, immutable";

    // A shared cache must not keep personal data that outlives its deletion.
    private static final String PRIVATE_CACHE_CONTROL = "private, no-store";

    // Keys are "<prefix>/<uuid>.<ext>": anything else after the public base
    // (dot segments, escapes, a query) could resolve elsewhere in a browser.
    private static final Pattern CLEAN_KEY =
            Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]*(/[A-Za-z0-9_-][A-Za-z0-9._-]*)*");

    private final S3Client client;
    private final String bucket;
    private final Set<String> privateKeyPrefixes;
    private final String publicBase;

    public S3ObjectStorage(ObjectStorageProperties.S3 props) {
        this(newClient(props), props);
    }

    // Package-visible constructor for tests to inject a mock client.
    S3ObjectStorage(S3Client client, ObjectStorageProperties.S3 props) {
        this.client = client;
        this.bucket = requireConfigured(props.getBucket(), "bucket");
        this.privateKeyPrefixes = normalisedPrefixes(props.getPrivateKeyPrefixes());
        this.publicBase = withTrailingSlash(props.getPublicBaseUrl());
    }

    private static S3Client newClient(ObjectStorageProperties.S3 props) {
        return S3Client.builder()
                .region(Region.of(requireConfigured(props.getRegion(), "region")))
                .endpointOverride(URI.create(requireConfigured(props.getEndpoint(), "endpoint")))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        requireConfigured(props.getAccessKeyId(), "access-key-id"),
                        requireConfigured(props.getSecretAccessKey(), "secret-access-key"))))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(props.isPathStyleAccess())
                        // R2 answers an aws-chunked upload with a signature mismatch (403).
                        .chunkedEncodingEnabled(false)
                        .build())
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }

    @Override
    public StoredObjectRef put(String keyPrefix, byte[] data, String contentType) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("data must not be empty");
        }
        String key = StorageKeys.newKey(keyPrefix, contentType);
        try {
            client.putObject(PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(contentType)
                    .cacheControl(isPrivate(key) ? PRIVATE_CACHE_CONTROL : IMMUTABLE_CACHE_CONTROL)
                    .build(), RequestBody.fromBytes(data));
            return new StoredObjectRef(key);
        } catch (NoSuchKeyException e) {
            throw new ObjectStorageException("bucket not found while storing object", e);
        } catch (RuntimeException e) {
            throw new ObjectStorageException("could not store object", e);
        }
    }

    @Override
    public Optional<StoredObject> get(String key) {
        requireKey(key);
        try {
            ResponseBytes<GetObjectResponse> object = client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
            String contentType = object.response().contentType();
            if (contentType == null || contentType.isBlank()) {
                contentType = StorageKeys.contentTypeForKey(key);
            }
            return Optional.of(new StoredObject(object.asByteArray(), contentType));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (RuntimeException e) {
            throw new ObjectStorageException("could not read object", e);
        }
    }

    @Override
    public void delete(String key) {
        requireKey(key);
        try {
            client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
        } catch (NoSuchKeyException e) {
            // Idempotent: nothing to delete.
        } catch (RuntimeException e) {
            throw new ObjectStorageException("could not delete object", e);
        }
    }

    @Override
    public Optional<String> publicUrl(String key) {
        requireKey(key);
        if (publicBase == null || isPrivate(key)) {
            return Optional.empty();
        }
        return Optional.of(publicBase + key);
    }

    @Override
    public boolean isOwnPublicUrl(String url) {
        if (publicBase == null || url == null
                || url.length() <= publicBase.length() || !url.startsWith(publicBase)) {
            return false;
        }
        String key = url.substring(publicBase.length());
        return CLEAN_KEY.matcher(key).matches() && !isPrivate(key);
    }

    @Override
    public String providerName() {
        return "s3";
    }

    @Override
    public void close() {
        client.close();
    }

    private static String requireConfigured(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ObjectStorageException(
                    "ecclesiaflow.object-storage.s3." + name + " must be set when provider=s3");
        }
        return value;
    }

    private boolean isPrivate(String key) {
        for (String prefix : privateKeyPrefixes) {
            if (key.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> normalisedPrefixes(Set<String> prefixes) {
        Set<String> normalised = new LinkedHashSet<>();
        if (prefixes != null) {
            for (String prefix : prefixes) {
                String trimmed = prefix == null ? "" : prefix.trim().replaceAll("^/+", "").replaceAll("/+$", "");
                if (!trimmed.isEmpty()) {
                    normalised.add(trimmed);
                }
            }
        }
        return Set.copyOf(normalised);
    }

    private static String withTrailingSlash(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        String trimmed = baseUrl.trim();
        return trimmed.endsWith("/") ? trimmed : trimmed + "/";
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
    }
}
