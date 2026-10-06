package com.ecclesiaflow.platform.security.autoconfigure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The endpoints of a separate management port run in a child context with its own dispatcher
 * servlet: only a real server with both ports shows the chain reaching them, and the scrape absent
 * from the application port.
 */
@SpringBootTest(classes = PlatformManagementSecurityServerTest.ModuleApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.port=0",
                "management.endpoints.web.exposure.include=health,info,metrics,prometheus",
                "management.endpoint.health.probes.enabled=true",
                "management.health.defaults.enabled=false",
                // A Spring Boot test turns metric export off unless told otherwise.
                "management.prometheus.metrics.export.enabled=true",
                "spring.main.banner-mode=off"})
class PlatformManagementSecurityServerTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(MembersController.class)
    static class ModuleApplication {

        @Bean
        SecurityFilterChain applicationSecurityFilterChain(HttpSecurity http) throws Exception {
            return http
                    .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                    .exceptionHandling(errors -> errors.authenticationEntryPoint(
                            new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                    .build();
        }
    }

    @RestController
    static class MembersController {

        @GetMapping("/members/me")
        String me() {
            return "member";
        }
    }

    @LocalServerPort
    private int applicationPort;

    @LocalManagementPort
    private int managementPort;

    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("the management port serves the scrape and the probes to a caller with no token")
    void scrapeAndProbes() throws Exception {
        HttpResponse<String> scrape = get(managementPort, "/actuator/prometheus");

        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.body()).contains("# HELP");
        assertThat(get(managementPort, "/actuator/health").statusCode()).isEqualTo(200);
        assertThat(get(managementPort, "/actuator/health/readiness").statusCode()).isEqualTo(200);
        assertThat(get(managementPort, "/actuator/info").statusCode()).isEqualTo(200);
    }

    // The chain refuses with 403; the server's error dispatch then crosses the module's chain,
    // which answers 401. Either way nothing is served.
    @Test
    @DisplayName("the management port refuses the other endpoints")
    void otherEndpoints() throws Exception {
        for (String path : new String[]{"/actuator/metrics", "/actuator/metrics/jvm.memory.used"}) {
            HttpResponse<String> refused = get(managementPort, path);

            assertThat(refused.statusCode()).as(path).isIn(401, 403);
            assertThat(refused.body()).as(path).doesNotContain("jvm.memory");
        }
    }

    @Test
    @DisplayName("the application port serves no metrics and keeps its own chain")
    void applicationPort() throws Exception {
        HttpResponse<String> scrape = get(applicationPort, "/actuator/prometheus");

        assertThat(scrape.statusCode()).isNotEqualTo(200);
        assertThat(scrape.body()).doesNotContain("# HELP");
        assertThat(get(applicationPort, "/members/me").statusCode()).isEqualTo(401);
    }
}
