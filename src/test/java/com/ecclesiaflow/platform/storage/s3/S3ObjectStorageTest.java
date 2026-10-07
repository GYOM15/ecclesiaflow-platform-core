package com.ecclesiaflow.platform.storage.s3;

import com.ecclesiaflow.platform.storage.ObjectStorage;
import com.ecclesiaflow.platform.storage.ObjectStorageException;
import com.ecclesiaflow.platform.storage.ObjectStorageProperties;
import com.ecclesiaflow.platform.storage.StoredObject;
import com.ecclesiaflow.platform.storage.StoredObjectRef;
import okhttp3.Headers;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("S3ObjectStorage - S3/R2 adapter (mocked client)")
class S3ObjectStorageTest {

    private static final String BUCKET = "ecclesiaflow-media";

    private S3Client client;
    private ObjectStorage storage;

    @BeforeEach
    void setUp() {
        client = mock(S3Client.class);
        storage = new S3ObjectStorage(client, props(null));
    }

    private static ObjectStorageProperties.S3 props(String publicBaseUrl) {
        ObjectStorageProperties.S3 props = new ObjectStorageProperties.S3();
        props.setBucket(BUCKET);
        props.setPublicBaseUrl(publicBaseUrl);
        return props;
    }

    private String cacheControlOfPut(ObjectStorage target, String keyPrefix) {
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        target.put(keyPrefix, "bytes".getBytes(StandardCharsets.UTF_8), "image/jpeg");
        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(captor.capture(), any(RequestBody.class));
        return captor.getValue().cacheControl();
    }

    @Test
    @DisplayName("put stores under a generated key with the content type and an immutable cache header")
    void putStoresWithImmutableCache() {
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        StoredObjectRef ref = storage.put("church-logos", "bytes".getBytes(StandardCharsets.UTF_8), "image/webp");

        assertThat(ref.key()).matches("church-logos/[0-9a-fA-F-]{36}\\.webp");
        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(captor.capture(), any(RequestBody.class));
        PutObjectRequest req = captor.getValue();
        assertThat(req.bucket()).isEqualTo(BUCKET);
        assertThat(req.key()).isEqualTo(ref.key());
        assertThat(req.contentType()).isEqualTo("image/webp");
        assertThat(req.cacheControl()).isEqualTo("public, max-age=31536000, immutable");
    }

    @Test
    @DisplayName("a tenant segment under member-photos keeps the photo private")
    void tenantSegmentedMemberPhotoPrivate() {
        assertThat(cacheControlOfPut(storage, "member-photos/3f2a7c1e-0000-0000-0000-000000000001"))
                .isEqualTo("private, no-store");
    }

    @Test
    @DisplayName("a prefix that merely starts like member-photos stays public")
    void lookalikePrefixPublic() {
        assertThat(cacheControlOfPut(storage, "member-photos-archive")).contains("public");
    }

    @Test
    @DisplayName("configured private prefixes replace the default and are normalised")
    void configuredPrivatePrefixes() {
        ObjectStorageProperties.S3 props = props(null);
        props.setPrivateKeyPrefixes(new HashSet<>(Arrays.asList("/receipts/", " ", null)));
        ObjectStorage configured = new S3ObjectStorage(client, props);

        assertThat(cacheControlOfPut(configured, "receipts")).isEqualTo("private, no-store");
    }

    @Test
    @DisplayName("no private prefix at all makes every object public")
    void noPrivatePrefixes() {
        ObjectStorageProperties.S3 props = props(null);
        props.setPrivateKeyPrefixes(null);
        ObjectStorage configured = new S3ObjectStorage(client, props);

        assertThat(cacheControlOfPut(configured, "member-photos")).contains("public");
    }

    @Test
    @DisplayName("a missing bucket is a configuration error")
    void missingBucket() {
        ObjectStorageProperties.S3 props = new ObjectStorageProperties.S3();

        assertThatThrownBy(() -> new S3ObjectStorage(client, props))
                .isInstanceOf(ObjectStorageException.class)
                .hasMessageContaining("bucket");
    }

    @Nested
    @DisplayName("public URLs")
    class PublicUrls {

        private final ObjectStorage cdn = new S3ObjectStorage(mock(S3Client.class), props("https://cdn.example.com"));

        @Test
        @DisplayName("a public object is served under the public base")
        void publicUrlOfPublicObject() {
            assertThat(cdn.publicUrl("church-logos/a.png")).contains("https://cdn.example.com/church-logos/a.png");
        }

        @Test
        @DisplayName("a base with a trailing slash yields no double slash")
        void trailingSlashBase() {
            ObjectStorage slashed = new S3ObjectStorage(mock(S3Client.class), props(" https://cdn.example.com/ "));

            assertThat(slashed.publicUrl("church-logos/a.png")).contains("https://cdn.example.com/church-logos/a.png");
            assertThat(slashed.isOwnPublicUrl("https://cdn.example.com/images/a.png")).isTrue();
        }

        @Test
        @DisplayName("a member photo has no public URL")
        void memberPhotoHasNoPublicUrl() {
            assertThat(cdn.publicUrl("member-photos/a.jpg")).isEmpty();
        }

        @Test
        @DisplayName("without a public base nothing has a public URL and no URL is ours")
        void noPublicBase() {
            ObjectStorage blankBase = new S3ObjectStorage(mock(S3Client.class), props("  "));

            for (ObjectStorage store : new ObjectStorage[]{storage, blankBase}) {
                assertThat(store.publicUrl("church-logos/a.png")).isEmpty();
                assertThat(store.isOwnPublicUrl("https://cdn.example.com/church-logos/a.png")).isFalse();
            }
        }

        @Test
        @DisplayName("publicUrl rejects a blank key")
        void blankKey() {
            assertThatThrownBy(() -> cdn.publicUrl(" ")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("an object under the public base is ours")
        void ownUrl() {
            assertThat(cdn.isOwnPublicUrl("https://cdn.example.com/images/a.png")).isTrue();
        }

        @Test
        @DisplayName("look-alike hosts, embedded bases, the bare base and null are not ours")
        void foreignUrls() {
            assertThat(cdn.isOwnPublicUrl("https://cdn.example.com.evil.net/images/a.png")).isFalse();
            assertThat(cdn.isOwnPublicUrl("https://evil.net/https://cdn.example.com/images/a.png")).isFalse();
            assertThat(cdn.isOwnPublicUrl("https://cdn.example.com@evil.net/a.png")).isFalse();
            assertThat(cdn.isOwnPublicUrl("https://cdn.example.com/")).isFalse();
            assertThat(cdn.isOwnPublicUrl("https://cdn.example.com")).isFalse();
            assertThat(cdn.isOwnPublicUrl(null)).isFalse();
        }

        @Test
        @DisplayName("dot segments, encodings, queries and backslashes cannot smuggle a path")
        void uncleanPathsNotOurs() {
            assertThat(cdn.isOwnPublicUrl("https://cdn.example.com/church-logos/../member-photos/a.jpg")).isFalse();
            assertThat(cdn.isOwnPublicUrl("https://cdn.example.com/church-logos/%2e%2e/member-photos/a.jpg"))
                    .isFalse();
            assertThat(cdn.isOwnPublicUrl("https://cdn.example.com/./images/a.png")).isFalse();
            assertThat(cdn.isOwnPublicUrl("https://cdn.example.com/images//a.png")).isFalse();
            assertThat(cdn.isOwnPublicUrl("https://cdn.example.com/images\\a.png")).isFalse();
            assertThat(cdn.isOwnPublicUrl("https://cdn.example.com/images/a.png?x=1")).isFalse();
            assertThat(cdn.isOwnPublicUrl("https://cdn.example.com/images/a.png ")).isFalse();
        }

        @Test
        @DisplayName("a URL to a private object is not an acceptable public URL")
        void privateObjectUrlNotOurs() {
            assertThat(cdn.isOwnPublicUrl("https://cdn.example.com/member-photos/a.jpg")).isFalse();
        }

        @Test
        @DisplayName("the key behind one of our public URLs is the path after the base")
        void keyOfOwnUrl() {
            assertThat(cdn.keyOfOwnPublicUrl("https://cdn.example.com/images/church-1/a.png"))
                    .contains("images/church-1/a.png");
        }

        @Test
        @DisplayName("another host, another base, a dot segment or a private object yield no key")
        void noKeyBehindForeignUrls() {
            ObjectStorage underPath = new S3ObjectStorage(mock(S3Client.class), props("https://cdn.example.com/media"));

            assertThat(cdn.keyOfOwnPublicUrl("https://evil.net/images/a.png")).isEmpty();
            assertThat(cdn.keyOfOwnPublicUrl("https://cdn.example.com.evil.net/images/a.png")).isEmpty();
            assertThat(underPath.keyOfOwnPublicUrl("https://cdn.example.com/images/a.png")).isEmpty();
            assertThat(underPath.keyOfOwnPublicUrl("https://cdn.example.com/media-old/images/a.png")).isEmpty();
            assertThat(underPath.keyOfOwnPublicUrl("https://cdn.example.com/media/images/a.png"))
                    .contains("images/a.png");
            assertThat(cdn.keyOfOwnPublicUrl("https://cdn.example.com/images/../member-photos/a.jpg")).isEmpty();
            assertThat(cdn.keyOfOwnPublicUrl("https://cdn.example.com/../images/a.png")).isEmpty();
            assertThat(cdn.keyOfOwnPublicUrl("https://cdn.example.com/member-photos/a.jpg")).isEmpty();
            assertThat(cdn.keyOfOwnPublicUrl("https://cdn.example.com/")).isEmpty();
            assertThat(cdn.keyOfOwnPublicUrl(null)).isEmpty();
        }

        @Test
        @DisplayName("without a public base no URL has a key")
        void noKeyWithoutPublicBase() {
            assertThat(storage.keyOfOwnPublicUrl("https://cdn.example.com/images/a.png")).isEmpty();
        }
    }

    @Nested
    @DisplayName("on the wire (real client, local endpoint)")
    class OnTheWire {

        private MockWebServer r2;
        private S3ObjectStorage wired;

        @BeforeEach
        void startEndpoint() throws IOException {
            r2 = new MockWebServer();
            r2.start();
            ObjectStorageProperties.S3 props = props(null);
            props.setEndpoint(r2.url("/").toString());
            props.setAccessKeyId("test-key");
            props.setSecretAccessKey("test-secret");
            wired = new S3ObjectStorage(props);
        }

        @AfterEach
        void stopEndpoint() throws IOException {
            wired.close();
            r2.shutdown();
        }

        // A single PUT's ETag is the body's MD5, which the SDK may check.
        private RecordedRequest upload(byte[] data) throws Exception {
            String md5 = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
            r2.enqueue(new MockResponse().setResponseCode(200)
                    .setSocketPolicy(SocketPolicy.EXPECT_CONTINUE)
                    .setHeader("ETag", "\"" + md5 + "\""));
            wired.put("church-logos", data, "image/png");
            return r2.takeRequest(5, TimeUnit.SECONDS);
        }

        @Test
        @DisplayName("an upload is one plain body, never aws-chunked, which R2 refuses")
        void uploadIsNotChunked() throws Exception {
            byte[] data = "png-bytes".getBytes(StandardCharsets.UTF_8);

            RecordedRequest request = upload(data);

            assertThat(request.getMethod()).isEqualTo("PUT");
            assertThat(request.getPath()).startsWith("/" + BUCKET + "/church-logos/");
            assertThat(request.getHeader("Content-Encoding")).isNull();
            assertThat(request.getHeader("x-amz-content-sha256")).doesNotStartWith("STREAMING-");
            assertThat(request.getHeader("x-amz-decoded-content-length")).isNull();
            assertThat(request.getBody().readByteArray()).isEqualTo(data);
        }

        @Test
        @DisplayName("an upload carries no flexible checksum, as with the SDK line R2 was validated on")
        void uploadCarriesNoFlexibleChecksum() throws Exception {
            RecordedRequest request = upload("png-bytes".getBytes(StandardCharsets.UTF_8));

            assertThat(checksumHeaders(request.getHeaders())).isEmpty();
            assertThat(request.getHeader("x-amz-trailer")).isNull();
        }

        @Test
        @DisplayName("a read carries no checksum and does not ask the bucket for one")
        void readCarriesNoChecksum() throws InterruptedException {
            r2.enqueue(new MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "image/png")
                    .setBody("png-bytes"));

            assertThat(wired.get("church-logos/a.png")).isPresent();

            RecordedRequest request = r2.takeRequest(5, TimeUnit.SECONDS);
            assertThat(request.getMethod()).isEqualTo("GET");
            assertThat(checksumHeaders(request.getHeaders())).isEmpty();
            assertThat(request.getHeader("x-amz-checksum-mode")).isNull();
        }

        private static Set<String> checksumHeaders(Headers headers) {
            Set<String> names = new HashSet<>();
            for (String name : headers.names()) {
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.startsWith("x-amz-checksum-") || lower.equals("x-amz-sdk-checksum-algorithm")) {
                    names.add(name);
                }
            }
            return names;
        }
    }

    @Test
    @DisplayName("a member photo is never cacheable by a shared cache (CDN)")
    void memberPhotoNotPubliclyCached() {
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        storage.put("member-photos", "bytes".getBytes(StandardCharsets.UTF_8), "image/jpeg");

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(captor.capture(), any(RequestBody.class));
        assertThat(captor.getValue().cacheControl())
                .doesNotContain("public")
                .contains("private")
                .contains("no-store");
    }

    @Test
    @DisplayName("get returns the bytes and the response content type")
    void getReturnsBytes() {
        byte[] data = "img".getBytes(StandardCharsets.UTF_8);
        when(client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(
                        GetObjectResponse.builder().contentType("image/png").build(), data));

        StoredObject object = storage.get("member-photos/abc.png").orElseThrow();

        assertThat(object.data()).isEqualTo(data);
        assertThat(object.contentType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("get falls back to the key extension when the response has no content type")
    void getFallsBackToKeyExtension() {
        when(client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(
                        GetObjectResponse.builder().build(), "x".getBytes(StandardCharsets.UTF_8)));

        assertThat(storage.get("logos/a.jpg").orElseThrow().contentType()).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("get on a missing key is empty, not an error")
    void getMissingIsEmpty() {
        when(client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().build());

        assertThat(storage.get("logos/missing.png")).isEmpty();
    }

    @Test
    @DisplayName("delete calls the SDK and swallows a missing key")
    void deleteIdempotent() {
        when(client.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(DeleteObjectResponse.builder().build());

        storage.delete("logos/a.png");
        verify(client).deleteObject(any(DeleteObjectRequest.class));

        when(client.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().build());
        // Must not throw.
        storage.delete("logos/a.png");
    }

    @Test
    @DisplayName("an SDK failure surfaces as ObjectStorageException, not a leaked SDK type")
    void sdkFailureWrapped() {
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().message("boom").build());

        assertThatThrownBy(() -> storage.put("logos", "x".getBytes(StandardCharsets.UTF_8), "image/png"))
                .isInstanceOf(ObjectStorageException.class);
    }

    @Test
    @DisplayName("providerName is s3")
    void providerName() {
        assertThat(storage.providerName()).isEqualTo("s3");
    }

    @Test
    @DisplayName("empty or missing bytes are refused before any call to the bucket")
    void emptyDataRejected() {
        assertThatThrownBy(() -> storage.put("logos", new byte[0], "image/png"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.put("logos", null, "image/png"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("a blank or missing key is refused before any call to the bucket")
    void blankKeyRejected() {
        assertThatThrownBy(() -> storage.get(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.delete(null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("read and delete failures surface as ObjectStorageException too")
    void readAndDeleteFailuresWrapped() {
        when(client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(S3Exception.builder().message("boom").build());
        when(client.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(S3Exception.builder().message("boom").build());

        assertThatThrownBy(() -> storage.get("logos/a.png"))
                .isInstanceOf(ObjectStorageException.class)
                .hasMessageContaining("read")
                .hasCauseInstanceOf(S3Exception.class);
        assertThatThrownBy(() -> storage.delete("logos/a.png"))
                .isInstanceOf(ObjectStorageException.class)
                .hasMessageContaining("delete")
                .hasCauseInstanceOf(S3Exception.class);
    }

    @Test
    @DisplayName("a missing setting names the property to set instead of failing inside the SDK")
    void missingSettingNamesTheProperty() {
        ObjectStorageProperties.S3 props = new ObjectStorageProperties.S3();
        props.setEndpoint(" ");

        assertThatThrownBy(() -> new S3ObjectStorage(props))
                .isInstanceOf(ObjectStorageException.class)
                .hasMessageContaining("ecclesiaflow.object-storage.s3.endpoint");

        props.setEndpoint("https://account.r2.cloudflarestorage.com");
        props.setAccessKeyId("key");
        props.setSecretAccessKey("secret");
        assertThatThrownBy(() -> new S3ObjectStorage(props))
                .isInstanceOf(ObjectStorageException.class)
                .hasMessageContaining("ecclesiaflow.object-storage.s3.bucket");
    }
}
