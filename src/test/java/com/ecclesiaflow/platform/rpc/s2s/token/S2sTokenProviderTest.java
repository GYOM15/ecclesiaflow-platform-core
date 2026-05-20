package com.ecclesiaflow.platform.rpc.s2s.token;
import com.ecclesiaflow.platform.rpc.s2s.S2sProperties;


import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end exercise of the token pipeline (client + cache + provider) against
 * a real HTTP endpoint hosted by MockWebServer. Verifies caching, refresh,
 * error handling and single-flight concurrency through the public API.
 */
class S2sTokenProviderTest {

    private MockWebServer keycloak;
    private S2sProperties props;
    private S2sTokenProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        keycloak = new MockWebServer();
        keycloak.start();

        props = new S2sProperties();
        props.setClientId("ecclesiaflow-backend");
        props.setClientSecret("test-secret");
        props.setTokenUrl(keycloak.url("/realms/ecclesiaflow/protocol/openid-connect/token").toString());
        props.setJwksUri("http://unused/jwks");
        props.setIssuer("http://unused");
        props.setRefreshLeewaySeconds(5);

        provider = new S2sTokenProvider(new S2sTokenClient(props), new S2sTokenCache(), props);
    }

    @AfterEach
    void tearDown() throws IOException {
        keycloak.shutdown();
    }

    @Test
    void firstCallFetchesTokenAndCachesIt() throws InterruptedException {
        keycloak.enqueue(jsonResponse(200, "{\"access_token\":\"jwt-1\",\"expires_in\":300}"));

        assertThat(provider.getToken()).isEqualTo("jwt-1");
        assertThat(provider.getToken()).isEqualTo("jwt-1"); // cache hit, no new request

        assertThat(keycloak.getRequestCount()).isEqualTo(1);

        RecordedRequest req = keycloak.takeRequest();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getHeader("Content-Type")).isEqualTo("application/x-www-form-urlencoded");
        String body = req.getBody().readUtf8();
        assertThat(body).contains("grant_type=client_credentials");
        assertThat(body).contains("client_id=ecclesiaflow-backend");
        assertThat(body).contains("client_secret=test-secret");
    }

    @Test
    void expiredTokenTriggersRefresh() {
        // expires_in=1 with leeway=5 → cache is invalid from the start, second call refreshes
        keycloak.enqueue(jsonResponse(200, "{\"access_token\":\"jwt-old\",\"expires_in\":1}"));
        keycloak.enqueue(jsonResponse(200, "{\"access_token\":\"jwt-fresh\",\"expires_in\":300}"));

        assertThat(provider.getToken()).isEqualTo("jwt-old");
        assertThat(provider.getToken()).isEqualTo("jwt-fresh");
        assertThat(keycloak.getRequestCount()).isEqualTo(2);
    }

    @Test
    void rejectedCredentialsThrowsS2sTokenException() {
        keycloak.enqueue(jsonResponse(401, "{\"error\":\"invalid_client\",\"error_description\":\"bad secret\"}"));

        assertThatThrownBy(provider::getToken)
                .isInstanceOf(S2sTokenException.class)
                .hasMessageContaining("status=401")
                .hasMessageContaining("invalid_client");
    }

    @Test
    void malformedResponseThrowsS2sTokenException() {
        keycloak.enqueue(jsonResponse(200, "{\"only_field\":\"nope\"}"));

        assertThatThrownBy(provider::getToken)
                .isInstanceOf(S2sTokenException.class)
                .hasMessageContaining("missing access_token");
    }

    @Test
    void concurrentCallsResultInSingleFetch() throws InterruptedException {
        keycloak.enqueue(jsonResponse(200, "{\"access_token\":\"jwt-1\",\"expires_in\":300}"));

        int callers = 16;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(callers);
        var pool = Executors.newFixedThreadPool(callers);

        for (int i = 0; i < callers; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    assertThat(provider.getToken()).isEqualTo("jwt-1");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        // Exactly one network call: subsequent callers read the cache populated by the first refresh.
        assertThat(keycloak.getRequestCount()).isEqualTo(1);
    }

    private static MockResponse jsonResponse(int status, String body) {
        return new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
