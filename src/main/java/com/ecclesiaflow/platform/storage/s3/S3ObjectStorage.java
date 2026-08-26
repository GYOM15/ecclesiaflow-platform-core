package com.ecclesiaflow.platform.storage.s3;

import com.ecclesiaflow.platform.storage.ObjectStorage;
import com.ecclesiaflow.platform.storage.ObjectStorageException;
import com.ecclesiaflow.platform.storage.ObjectStorageProperties;
import com.ecclesiaflow.platform.storage.StorageKeys;
import com.ecclesiaflow.platform.storage.StoredObject;
import com.ecclesiaflow.platform.storage.StoredObjectRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.Optional;

/**
 * S3-compatible {@link ObjectStorage} adapter — the production backend, pointed
 * at Cloudflare R2 by default (any S3 API works: AWS S3, MinIO).
 *
 * <p>Mirrors the proven frontend R2 client ({@code lib/storage/r2.ts}):
 * path-style addressing, {@code region=auto}, random content-addressed keys, and
 * a one-year immutable cache header (safe because keys never change). The bucket
 * stays PRIVATE; public assets are served through a CDN base URL, access-
 * controlled assets are proxied back through the backend via {@link #get}.</p>
 *
 * <p>Implements {@link AutoCloseable} so Spring closes the underlying
 * {@link S3Client} (and its connection pool) on context shutdown.</p>
 */
public class S3ObjectStorage implements ObjectStorage, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectStorage.class);

    /** One year, immutable — keys are unique per upload so content never changes. */
    private static final String IMMUTABLE_CACHE_CONTROL = "public, max-age=31536000, immutable";

    private final S3Client client;
    private final String bucket;

    public S3ObjectStorage(ObjectStorageProperties.S3 props) {
        this.bucket = requireConfigured(props.getBucket(), "bucket");
        this.client = S3Client.builder()
                .region(Region.of(requireConfigured(props.getRegion(), "region")))
                .endpointOverride(URI.create(requireConfigured(props.getEndpoint(), "endpoint")))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        requireConfigured(props.getAccessKeyId(), "access-key-id"),
                        requireConfigured(props.getSecretAccessKey(), "secret-access-key"))))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(props.isPathStyleAccess())
                        .build())
                .httpClient(UrlConnectionHttpClient.create())
                .build();
        // Never log the endpoint host with credentials; bucket name only.
        log.info("OBJECT-STORAGE: s3 adapter active, bucket={}", bucket);
    }

    // Package-visible constructor for tests to inject a mock client.
    S3ObjectStorage(S3Client client, String bucket) {
        this.client = client;
        this.bucket = bucket;
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
                    .cacheControl(IMMUTABLE_CACHE_CONTROL)
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

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
    }
}
