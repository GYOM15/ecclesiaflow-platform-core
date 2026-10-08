package com.ecclesiaflow.platform.storage.s3;

import com.ecclesiaflow.platform.storage.ObjectStorageException;
import com.ecclesiaflow.platform.storage.ObjectStorageProperties;
import com.ecclesiaflow.platform.storage.StoredObject;
import com.ecclesiaflow.platform.storage.StoredObjectNotFoundException;
import com.ecclesiaflow.platform.storage.StoredObjectRef;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.net.URI;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The adapter's own client against a real S3 API: the server must accept its signatures and bodies. */
class S3ObjectStorageIntegrationTest {

    private static final String BUCKET = "ecclesiaflow-media";
    private static final String ACCESS_KEY = "test-access-key";
    private static final String SECRET_KEY = "test-secret-key";
    // R2 takes "auto"; the client settings under test do not depend on the region.
    private static final String REGION = "us-east-1";
    private static final int S3_PORT = 8333;
    // One identity, so the server verifies every request's signature.
    private static final String IDENTITIES = """
            {"identities": [{"name": "test",
              "credentials": [{"accessKey": "%s", "secretKey": "%s"}],
              "actions": ["Admin", "Read", "Write", "List", "Tagging"]}]}
            """.formatted(ACCESS_KEY, SECRET_KEY);

    @SuppressWarnings("resource")
    private static final GenericContainer<?> S3_SERVER =
            new GenericContainer<>(DockerImageName.parse("chrislusf/seaweedfs:3.80"))
                    .withCopyToContainer(Transferable.of(IDENTITIES), "/etc/seaweedfs/s3.json")
                    .withCommand("server", "-dir=/data", "-volume.max=5", "-master.volumeSizeLimitMB=32",
                            "-s3", "-s3.port=" + S3_PORT, "-s3.config=/etc/seaweedfs/s3.json")
                    .withExposedPorts(S3_PORT)
                    .waitingFor(Wait.forHttp("/").forPort(S3_PORT).forStatusCode(403));

    private static String endpoint;
    private static S3Client admin;
    private static S3ObjectStorage storage;

    @BeforeAll
    static void startServer() {
        S3_SERVER.start();
        endpoint = "http://" + S3_SERVER.getHost() + ":" + S3_SERVER.getMappedPort(S3_PORT);

        admin = S3Client.builder()
                .region(Region.of(REGION))
                .endpointOverride(URI.create(endpoint))
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClient(UrlConnectionHttpClient.create())
                .build();
        admin.createBucket(request -> request.bucket(BUCKET));

        storage = new S3ObjectStorage(properties(SECRET_KEY));
    }

    private static ObjectStorageProperties.S3 properties(String secretKey) {
        ObjectStorageProperties.S3 props = new ObjectStorageProperties.S3();
        props.setEndpoint(endpoint);
        props.setRegion(REGION);
        props.setAccessKeyId(ACCESS_KEY);
        props.setSecretAccessKey(secretKey);
        props.setBucket(BUCKET);
        return props;
    }

    @AfterAll
    static void stopServer() {
        storage.close();
        admin.close();
        S3_SERVER.stop();
    }

    @Test
    @DisplayName("an object stored through the adapter reads back byte for byte, with its content type")
    void storedObjectReadsBack() {
        byte[] data = new byte[300 * 1024];
        new Random(42).nextBytes(data);

        StoredObjectRef ref = storage.put("church-logos", data, "image/png");
        StoredObject read = storage.get(ref.key()).orElseThrow();

        assertThat(read.data()).isEqualTo(data);
        assertThat(read.contentType()).isEqualTo("image/png");
        assertThat(admin.headObject(request -> request.bucket(BUCKET).key(ref.key())).cacheControl())
                .isEqualTo("public, max-age=31536000, immutable");
    }

    @Test
    @DisplayName("a member photo is stored with a cache header no shared cache may keep")
    void memberPhotoStoredPrivate() {
        StoredObjectRef ref = storage.put("member-photos", new byte[]{1, 2, 3}, "image/jpeg");

        assertThat(admin.headObject(request -> request.bucket(BUCKET).key(ref.key())).cacheControl())
                .isEqualTo("private, no-store");
    }

    @Test
    @DisplayName("the server checks signatures: a wrong secret is refused")
    void wrongSecretRefused() {
        try (S3ObjectStorage forged = new S3ObjectStorage(properties("not-the-secret"))) {
            assertThatThrownBy(() -> forged.put("church-logos", new byte[]{1}, "image/png"))
                    .isInstanceOf(ObjectStorageException.class);
        }
    }

    @Test
    @DisplayName("a copy reads back byte for byte, with the source's content type and cache header")
    void copyKeepsBytesAndHeaders() {
        byte[] data = new byte[64 * 1024];
        new Random(7).nextBytes(data);
        StoredObjectRef source = storage.put("uploads/church-1", data, "image/webp");
        String target = "images/church-1/" + UUID.randomUUID() + ".webp";

        storage.copy(source.key(), target);

        assertThat(storage.get(target).orElseThrow().data()).isEqualTo(data);
        HeadObjectResponse head = admin.headObject(request -> request.bucket(BUCKET).key(target));
        assertThat(head.contentType()).isEqualTo("image/webp");
        assertThat(head.cacheControl()).isEqualTo("public, max-age=31536000, immutable");
        assertThat(storage.get(source.key())).isPresent();
    }

    @Test
    @DisplayName("copying an absent object is reported as not found")
    void copyOfAbsentObject() {
        assertThatThrownBy(() -> storage.copy("uploads/" + UUID.randomUUID() + ".png", "images/a.png"))
                .isInstanceOf(StoredObjectNotFoundException.class);
    }

    @Test
    @DisplayName("a deleted object is gone, and deleting it again is not an error")
    void deleteIsIdempotent() {
        StoredObjectRef ref = storage.put("church-logos", new byte[]{1, 2, 3}, "image/png");

        storage.delete(ref.key());
        storage.delete(ref.key());

        assertThat(storage.get(ref.key())).isEmpty();
    }
}
