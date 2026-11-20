package com.ecclesiaflow.platform.webhooks.web;

import com.ecclesiaflow.platform.webhooks.autoconfigure.PlatformWebhookBodyLimitAutoConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A mock request hands the filter its body whole; only a real server shows a chunked body of unknown length
 * refused and the route behind it never reached.
 */
@SpringBootTest(classes = WebhookBodyLimitServerTest.Module.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "ecclesiaflow.webhooks.body-limit.enabled=true",
                "ecclesiaflow.webhooks.body-limit.path-prefixes=/payments/webhooks/",
                "ecclesiaflow.webhooks.body-limit.max-bytes=1024",
                "spring.main.banner-mode=off"})
class WebhookBodyLimitServerTest {

    static final AtomicInteger REACHED = new AtomicInteger();

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class, PlatformWebhookBodyLimitAutoConfiguration.class})
    @Import(NotificationController.class)
    static class Module {
    }

    @RestController
    static class NotificationController {

        @PostMapping("/payments/webhooks/test")
        String receive(@RequestBody byte[] body) {
            REACHED.incrementAndGet();
            return Integer.toString(body.length);
        }
    }

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @BeforeEach
    void reset() {
        REACHED.set(0);
    }

    private HttpResponse<String> post(HttpRequest.BodyPublisher body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/payments/webhooks/test"))
                .header("Content-Type", "application/json")
                .POST(body)
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("a declared length past the bound is refused before the route")
    void declaredTooLarge() throws Exception {
        HttpResponse<String> response = post(HttpRequest.BodyPublishers.ofByteArray(new byte[2_048]));

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("\"errorCode\":\"PAYLOAD_TOO_LARGE\"");
        assertThat(REACHED).hasValue(0);
    }

    @Test
    @DisplayName("a chunked body of unknown length past the bound is refused before the route")
    void chunkedTooLarge() throws Exception {
        HttpResponse<String> response = post(HttpRequest.BodyPublishers.ofInputStream(
                () -> new ByteArrayInputStream(new byte[2_048])));

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(REACHED).hasValue(0);
    }

    @Test
    @DisplayName("a chunked body within the bound reaches the route whole")
    void chunkedWithinTheBound() throws Exception {
        HttpResponse<String> response = post(HttpRequest.BodyPublishers.ofInputStream(
                () -> new ByteArrayInputStream(new byte[1_024])));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("1024");
        assertThat(REACHED).hasValue(1);
    }
}
