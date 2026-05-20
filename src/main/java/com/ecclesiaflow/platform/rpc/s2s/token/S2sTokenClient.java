package com.ecclesiaflow.platform.rpc.s2s.token;
import com.ecclesiaflow.platform.rpc.s2s.S2sProperties;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * Talks to Keycloak's token endpoint over the {@code client_credentials} grant.
 *
 * <p><strong>Single responsibility:</strong> issue one HTTP exchange and turn the
 * response into a {@link S2sToken}. No caching, no concurrency control, no logging —
 * those concerns live in other classes (see {@link S2sTokenCache} and the platform's
 * logging aspect).</p>
 *
 * <p>All failures surface as {@link S2sTokenException}. Callers translate them to
 * whatever transport-level status fits (typically gRPC {@code UNAVAILABLE}).</p>
 */
public class S2sTokenClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final S2sProperties props;
    private final HttpClient httpClient;

    public S2sTokenClient(S2sProperties props) {
        this(props, HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build());
    }

    /** Test-only constructor: lets the caller inject a mocked or instrumented HttpClient. */
    public S2sTokenClient(S2sProperties props, HttpClient httpClient) {
        this.props = props;
        this.httpClient = httpClient;
    }

    /**
     * Exchanges the configured client credentials for a fresh access token.
     *
     * @return a {@link S2sToken} carrying the raw JWT and its absolute expiry instant
     * @throws S2sTokenException if the token endpoint is unreachable, returns a non-2xx,
     *                           or returns a malformed payload
     */
    public S2sToken fetchToken() {
        String body = "grant_type=client_credentials"
                + "&client_id=" + URLEncoder.encode(props.getClientId(), StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(props.getClientSecret(), StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder(URI.create(props.getTokenUrl()))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new S2sTokenException("Failed to contact token endpoint: " + props.getTokenUrl(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new S2sTokenException("Interrupted while contacting token endpoint", e);
        }

        if (response.statusCode() != 200) {
            throw new S2sTokenException("Token endpoint rejected credentials: status=" + response.statusCode()
                    + " error=" + extractErrorCode(response.body()));
        }

        JsonNode json;
        try {
            json = JSON.readTree(response.body());
        } catch (IOException e) {
            throw new S2sTokenException("Token endpoint returned invalid JSON", e);
        }
        String accessToken = json.path("access_token").asText(null);
        long expiresIn = json.path("expires_in").asLong(0L);
        if (accessToken == null || expiresIn <= 0L) {
            throw new S2sTokenException("Token endpoint response missing access_token or expires_in");
        }

        return new S2sToken(accessToken, Instant.now().plusSeconds(expiresIn));
    }

    private static String extractErrorCode(String body) {
        if (body == null || body.isEmpty()) {
            return "unknown";
        }
        try {
            return JSON.readTree(body).path("error").asText("unknown");
        } catch (IOException e) {
            return "unparseable";
        }
    }
}
