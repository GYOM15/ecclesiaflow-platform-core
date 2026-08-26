package com.ecclesiaflow.platform.storage.s3;

import com.ecclesiaflow.platform.storage.ObjectStorage;
import com.ecclesiaflow.platform.storage.ObjectStorageException;
import com.ecclesiaflow.platform.storage.StoredObject;
import com.ecclesiaflow.platform.storage.StoredObjectRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("S3ObjectStorage - S3/R2 adapter (mocked client)")
class S3ObjectStorageTest {

    private static final String BUCKET = "ecclesiaflow-media";

    private S3Client client;
    private ObjectStorage storage;

    @BeforeEach
    void setUp() {
        client = mock(S3Client.class);
        storage = new S3ObjectStorage(client, BUCKET);
    }

    @Test
    @DisplayName("put stores under a generated key with the content type and an immutable cache header")
    void putStoresWithImmutableCache() {
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        StoredObjectRef ref = storage.put("member-photos", "bytes".getBytes(StandardCharsets.UTF_8), "image/webp");

        assertThat(ref.key()).matches("member-photos/[0-9a-fA-F-]{36}\\.webp");
        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(captor.capture(), any(RequestBody.class));
        PutObjectRequest req = captor.getValue();
        assertThat(req.bucket()).isEqualTo(BUCKET);
        assertThat(req.key()).isEqualTo(ref.key());
        assertThat(req.contentType()).isEqualTo("image/webp");
        assertThat(req.cacheControl()).contains("immutable");
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
}
