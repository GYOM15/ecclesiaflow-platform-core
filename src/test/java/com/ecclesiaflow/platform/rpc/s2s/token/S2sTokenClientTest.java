package com.ecclesiaflow.platform.rpc.s2s.token;

import com.ecclesiaflow.platform.rpc.s2s.S2sProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Every way the token endpoint can fail must surface as an S2sTokenException. */
class S2sTokenClientTest {

    private MockWebServer keycloak;
    private S2sProperties props;

    @BeforeEach
    void setUp() throws IOException {
        keycloak = new MockWebServer();
        keycloak.start();

        props = new S2sProperties();
        props.setClientId("ecclesiaflow-backend");
        props.setClientSecret("test-secret");
        props.setTokenUrl(keycloak.url("/realms/ecclesiaflow/protocol/openid-connect/token").toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        keycloak.shutdown();
    }

    @Test
    @DisplayName("an unreachable endpoint is reported with its cause")
    void unreachableEndpoint() throws IOException {
        props.setTokenUrl("http://127.0.0.1:" + freePort() + "/token");

        assertThatThrownBy(() -> new S2sTokenClient(props).fetchToken())
                .isInstanceOf(S2sTokenException.class)
                .hasMessageContaining("Failed to contact token endpoint")
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("an unreachable token endpoint does not put its URL in the exception message")
    void unreachableEndpointKeepsTheUrlOutOfTheMessage() throws Exception {
        props.setTokenUrl("http://keycloak:8080/realms/ecclesiaflow/protocol/openid-connect/token");
        HttpClient http = mock(HttpClient.class);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("Connection refused"));

        assertThatThrownBy(() -> new S2sTokenClient(props, http).fetchToken())
                .isInstanceOf(S2sTokenException.class)
                .hasMessageNotContaining("keycloak")
                .hasMessageNotContaining("http://")
                .hasMessageContaining("token endpoint")
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("an interrupted caller gets an exception and keeps its interrupt flag")
    void interruptedCaller() {
        keycloak.enqueue(json(200, "{\"access_token\":\"jwt\",\"expires_in\":300}")
                .setBodyDelay(5, TimeUnit.SECONDS));
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> new S2sTokenClient(props).fetchToken())
                    .isInstanceOf(S2sTokenException.class)
                    .hasMessageContaining("Interrupted")
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("a 200 whose body is not JSON is malformed, not a token")
    void invalidJson() {
        keycloak.enqueue(json(200, "<html>proxy error</html>"));

        assertThatThrownBy(() -> new S2sTokenClient(props).fetchToken())
                .isInstanceOf(S2sTokenException.class)
                .hasMessageContaining("invalid JSON")
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("a token without a lifetime cannot be cached safely and is refused")
    void tokenWithoutLifetime() {
        keycloak.enqueue(json(200, "{\"access_token\":\"jwt\",\"expires_in\":0}"));

        assertThatThrownBy(() -> new S2sTokenClient(props).fetchToken())
                .isInstanceOf(S2sTokenException.class)
                .hasMessageContaining("missing access_token or expires_in");
    }

    @Test
    @DisplayName("a refusal with no body or a non-JSON body still names a status and an error code")
    void refusalBodiesThatCarryNoErrorCode() {
        keycloak.enqueue(json(503, ""));
        keycloak.enqueue(json(502, "Bad Gateway"));
        S2sTokenClient client = new S2sTokenClient(props);

        assertThatThrownBy(client::fetchToken)
                .isInstanceOf(S2sTokenException.class)
                .hasMessageContaining("status=503")
                .hasMessageContaining("error=unknown");
        assertThatThrownBy(client::fetchToken)
                .isInstanceOf(S2sTokenException.class)
                .hasMessageContaining("status=502")
                .hasMessageContaining("error=unparseable");
    }

    @Test
    @DisplayName("a valid response yields the token and an expiry in the future")
    void validResponse() {
        keycloak.enqueue(json(200, "{\"access_token\":\"jwt-1\",\"expires_in\":300}"));

        S2sToken token = new S2sTokenClient(props).fetchToken();

        assertThat(token.accessToken()).isEqualTo("jwt-1");
        assertThat(token.expiry()).isAfter(java.time.Instant.now().plusSeconds(290));
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
