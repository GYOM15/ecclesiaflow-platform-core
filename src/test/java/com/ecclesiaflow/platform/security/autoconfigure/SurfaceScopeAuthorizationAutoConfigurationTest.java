package com.ecclesiaflow.platform.security.autoconfigure;

import com.ecclesiaflow.platform.contract.InvalidApiContractException;
import com.ecclesiaflow.platform.security.KeycloakJwtConverter;
import com.ecclesiaflow.platform.security.surface.InvalidSurfaceConfigurationException;
import com.ecclesiaflow.platform.security.surface.SurfaceProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Boots a module on a real server, through {@code @EnableAutoConfiguration} as a consuming service does,
 * with its contract, its own security chain and tokens decoded the way Keycloak's are converted.
 */
class SurfaceScopeAuthorizationAutoConfigurationTest {

    static final String CONTRACT = "ecclesiaflow.contract.locations=classpath:contract/surface-module.yaml";

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(ModuleController.class)
    static class ModuleApplication {

        @Bean
        JwtDecoder jwtDecoder() {
            return Tokens::decode;
        }

        /** {@code test.open} lists extra permitAll paths; {@code test.signup-open=false} closes the signup. */
        @Bean
        SecurityFilterChain moduleSecurityFilterChain(HttpSecurity http, JwtDecoder decoder, Environment env)
                throws Exception {
            String[] extraOpen = env.getProperty("test.open", String[].class, new String[0]);
            boolean signupOpen = env.getProperty("test.signup-open", Boolean.class, true);
            boolean everythingOpen = env.getProperty("test.everything-open", Boolean.class, false);
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(requests -> {
                        if (everythingOpen) {
                            requests.anyRequest().permitAll();
                            return;
                        }
                        if (signupOpen) {
                            requests.requestMatchers(HttpMethod.POST, "/module/public/signup").permitAll();
                        }
                        if (extraOpen.length > 0) {
                            requests.requestMatchers(extraOpen).permitAll();
                        }
                        requests.requestMatchers("/error", "/ws/**").permitAll()
                                .anyRequest().authenticated();
                    })
                    .oauth2ResourceServer(server -> server.jwt(jwt -> jwt
                            .decoder(decoder)
                            .jwtAuthenticationConverter(new KeycloakJwtConverter())))
                    .build();
        }
    }

    @RestController
    static class ModuleController {

        @GetMapping("/module/me")
        String me() {
            return "me";
        }

        @GetMapping("/module/console/members")
        String consoleMembers() {
            return "members";
        }

        @GetMapping("/module/moderation/reports")
        String reports() {
            return "reports";
        }

        @GetMapping("/module/ops/plans")
        String plans() {
            return "plans";
        }

        @GetMapping("/module/backoffice/accounts")
        String accounts() {
            return "accounts";
        }

        @GetMapping("/module/backoffice/journal")
        String journal() {
            return "journal";
        }

        @PostMapping("/module/public/signup")
        String signup() {
            return "signed-up";
        }

        @GetMapping("/module/unlisted")
        String unlisted() {
            return "unlisted";
        }

        @GetMapping("/ws/info")
        String socketInfo() {
            return "socket";
        }
    }

    /** Token values the fake decoder knows, shaped like the realm's. */
    static final class Tokens {

        static final String WEB = "web";
        static final String MOBILE = "mobile";
        static final String MODERATION = "moderation";
        static final String MODERATION_WITHOUT_ROLE = "moderation-without-role";
        static final String WEB_MODERATOR = "web-moderator";
        static final String BILLING_OPERATOR = "billing-operator";
        static final String BILLING_WITHOUT_ROLE = "billing-without-role";
        static final String SUPPORT = "support";
        static final String SUPER_ADMIN = "super-admin";
        static final String BACKOFFICE_WITHOUT_ROLE = "backoffice-without-role";
        static final String WEB_SUPER_ADMIN = "web-super-admin";
        static final String MODERATION_CLI = "moderation-cli";
        static final String BILLING_CLI = "billing-cli";
        static final String BACKOFFICE_FULL = "backoffice-full";

        /** The default scopes of the back-office client. */
        private static final String BACKOFFICE_SCOPES = "ef:backoffice:act ef:moderation:act";

        private static final Map<String, Map<String, Object>> CLAIMS = Map.ofEntries(
                Map.entry(WEB, Map.of("scope", "openid ef:surface:full", "azp", "ecclesiaflow-web")),
                Map.entry(MOBILE, Map.of("scope", "openid", "azp", "ecclesiaflow-mobile")),
                Map.entry(MODERATION, Map.of("scope", "ef:moderation:act", "azp", "ecclesiaflow-moderation",
                        "realm_access", Map.of("roles", List.of("PLATFORM_MODERATOR")))),
                Map.entry(MODERATION_WITHOUT_ROLE,
                        Map.of("scope", "ef:moderation:act", "azp", "ecclesiaflow-moderation")),
                Map.entry(WEB_MODERATOR, Map.of("scope", "openid ef:surface:full", "azp", "ecclesiaflow-web",
                        "realm_access", Map.of("roles", List.of("PLATFORM_MODERATOR")))),
                Map.entry(BILLING_OPERATOR,
                        Map.of("scope", "ef:billing:operate", "azp", "ecclesiaflow-billing-operations",
                                "realm_access", Map.of("roles", List.of("PLATFORM_BILLING")))),
                Map.entry(BILLING_WITHOUT_ROLE,
                        Map.of("scope", "ef:billing:operate", "azp", "ecclesiaflow-billing-operations")),
                Map.entry(SUPPORT, backOffice("SUPPORT")),
                Map.entry(SUPER_ADMIN, backOffice("SUPER_ADMIN")),
                Map.entry(BACKOFFICE_WITHOUT_ROLE,
                        Map.of("scope", BACKOFFICE_SCOPES, "azp", "ecclesiaflow-backoffice")),
                Map.entry(WEB_SUPER_ADMIN, Map.of("scope", "openid ef:surface:full", "azp", "ecclesiaflow-web",
                        "realm_access", Map.of("roles", List.of("SUPER_ADMIN", "SUPPORT")))),
                Map.entry(MODERATION_CLI, Map.of("scope", "ef:moderation:act", "azp", "ecclesiaflow-moderation-cli",
                        "realm_access", Map.of("roles", List.of("PLATFORM_MODERATOR")))),
                Map.entry(BILLING_CLI, Map.of("scope", "ef:billing:operate", "azp", "ecclesiaflow-billing-cli",
                        "realm_access", Map.of("roles", List.of("PLATFORM_BILLING")))),
                Map.entry(BACKOFFICE_FULL, Map.of("scope", "ef:surface:full " + BACKOFFICE_SCOPES,
                        "azp", "ecclesiaflow-backoffice", "realm_access", Map.of("roles", List.of("SUPER_ADMIN")))));

        private Tokens() {
        }

        private static Map<String, Object> backOffice(String role) {
            return Map.of("scope", BACKOFFICE_SCOPES, "azp", "ecclesiaflow-backoffice",
                    "realm_access", Map.of("roles", List.of(role)));
        }

        static Jwt decode(String token) {
            Map<String, Object> claims = CLAIMS.get(token);
            if (claims == null) {
                throw new BadJwtException("unknown test token");
            }
            Instant now = Instant.now();
            return Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .subject("3f2c1d4e-0000-4000-8000-00000000000" + Math.abs(token.hashCode() % 10))
                    .issuedAt(now)
                    .expiresAt(now.plusSeconds(300))
                    .claims(all -> all.putAll(claims))
                    .build();
        }
    }

    static ConfigurableApplicationContext boot(String... properties) {
        return new SpringApplicationBuilder(ModuleApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties("server.port=0", "spring.main.banner-mode=off", CONTRACT)
                .properties(properties)
                .run();
    }

    /** The root cause of a failed start, or the failure itself when nothing wraps it. */
    static Throwable startupFailure(String... properties) {
        Throwable failure = catchThrowable(() -> boot(properties).close());
        assertThat(failure).as("the module started").isNotNull();
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root;
    }

    abstract static class RunningModule {

        private final HttpClient client = HttpClient.newHttpClient();
        ConfigurableApplicationContext context;

        abstract String[] properties();

        @BeforeAll
        void start() {
            context = boot(properties());
        }

        @AfterAll
        void stop() {
            context.close();
        }

        HttpResponse<String> call(String method, String path, String token) throws IOException, InterruptedException {
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .method(method, HttpRequest.BodyPublishers.noBody());
            if (token != null) {
                request.header("Authorization", "Bearer " + token);
            }
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }

        int status(String method, String path, String token) throws IOException, InterruptedException {
            return call(method, path, token).statusCode();
        }

        double refused(String surface, String caller, String mode) {
            Search search = context.getBean(MeterRegistry.class).find("ef.surface.refused")
                    .tags("surface", surface, "caller", caller, "mode", mode);
            return search.counter() == null ? 0 : search.counter().count();
        }

        Double modeGauge(String mode) {
            var gauge = context.getBean(MeterRegistry.class).find("surface.mode").tag("mode", mode).gauge();
            return gauge == null ? null : gauge.value();
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("enforce, the default")
    class Enforce extends RunningModule {

        @Override
        String[] properties() {
            return new String[0];
        }

        @Test
        @DisplayName("a web token on a platform route is refused: ef:surface:full never opens moderation")
        void webTokenOnPlatform() throws Exception {
            HttpResponse<String> refused = call("GET", "/module/moderation/reports", Tokens.WEB);

            assertThat(refused.statusCode()).isEqualTo(403);
            assertThat(refused.body()).doesNotContain("reports");
            assertThat(status("GET", "/module/moderation/reports", Tokens.WEB_MODERATOR)).isEqualTo(403);
        }

        @Test
        @DisplayName("a moderation token opens a platform route and no console route")
        void moderationToken() throws Exception {
            assertThat(call("GET", "/module/moderation/reports", Tokens.MODERATION).body()).isEqualTo("reports");
            assertThat(status("GET", "/module/console/members", Tokens.MODERATION)).isEqualTo(403);
            assertThat(status("GET", "/module/moderation/reports", Tokens.MODERATION_WITHOUT_ROLE)).isEqualTo(403);
        }

        @Test
        @DisplayName("a billing operator opens billing operations only, which no moderation or web token reaches")
        void billingOperatorToken() throws Exception {
            assertThat(call("GET", "/module/ops/plans", Tokens.BILLING_OPERATOR).body()).isEqualTo("plans");
            assertThat(status("GET", "/module/moderation/reports", Tokens.BILLING_OPERATOR)).isEqualTo(403);
            assertThat(status("GET", "/module/console/members", Tokens.BILLING_OPERATOR)).isEqualTo(403);
            assertThat(status("GET", "/module/ops/plans", Tokens.BILLING_WITHOUT_ROLE)).isEqualTo(403);
            assertThat(status("GET", "/module/ops/plans", Tokens.MODERATION)).isEqualTo(403);
            assertThat(status("GET", "/module/ops/plans", Tokens.WEB)).isEqualTo(403);
        }

        @Test
        @DisplayName("support operations open to the back-office scope with the support or super admin role")
        void supportDuty() throws Exception {
            assertThat(call("GET", "/module/backoffice/accounts", Tokens.SUPPORT).body()).isEqualTo("accounts");
            assertThat(call("GET", "/module/backoffice/accounts", Tokens.SUPER_ADMIN).body()).isEqualTo("accounts");
            assertThat(status("GET", "/module/backoffice/accounts", Tokens.BACKOFFICE_WITHOUT_ROLE)).isEqualTo(403);
            assertThat(status("GET", "/module/backoffice/accounts", Tokens.MODERATION)).isEqualTo(403);
            assertThat(status("GET", "/module/backoffice/accounts", Tokens.BILLING_OPERATOR)).isEqualTo(403);
        }

        @Test
        @DisplayName("administration operations open to the super admin alone, never to the support")
        void administrationDuty() throws Exception {
            assertThat(call("GET", "/module/backoffice/journal", Tokens.SUPER_ADMIN).body()).isEqualTo("journal");
            assertThat(status("GET", "/module/backoffice/journal", Tokens.SUPPORT)).isEqualTo(403);
            assertThat(status("GET", "/module/backoffice/journal", Tokens.BACKOFFICE_WITHOUT_ROLE)).isEqualTo(403);
            assertThat(status("GET", "/module/backoffice/journal", Tokens.MODERATION)).isEqualTo(403);
        }

        @Test
        @DisplayName("a web token reaches no back-office operation, even the owner's with both roles")
        void webTokenOnBackOffice() throws Exception {
            assertThat(status("GET", "/module/backoffice/accounts", Tokens.WEB_SUPER_ADMIN)).isEqualTo(403);
            assertThat(status("GET", "/module/backoffice/journal", Tokens.WEB_SUPER_ADMIN)).isEqualTo(403);
            assertThat(status("GET", "/module/backoffice/accounts", Tokens.WEB)).isEqualTo(403);
            assertThat(refused("platform-administration", "console", "enforce")).isPositive();
        }

        @Test
        @DisplayName("a back-office token opens no moderation route without the moderator role")
        void backOfficeTokenOnModeration() throws Exception {
            assertThat(status("GET", "/module/moderation/reports", Tokens.SUPER_ADMIN)).isEqualTo(403);
            assertThat(status("GET", "/module/ops/plans", Tokens.SUPER_ADMIN)).isEqualTo(403);
        }

        @Test
        @DisplayName("a back-office or command-line token reaches no member, console or unlisted route")
        void operatingClientsReachPlatformOnly() throws Exception {
            for (String token : List.of(Tokens.SUPER_ADMIN, Tokens.SUPPORT, Tokens.MODERATION_CLI, Tokens.BILLING_CLI,
                    Tokens.BACKOFFICE_FULL)) {
                assertThat(status("GET", "/module/me", token)).as(token).isEqualTo(403);
                assertThat(status("GET", "/module/console/members", token)).as(token).isEqualTo(403);
                assertThat(status("GET", "/module/unlisted", token)).as(token).isEqualTo(403);
            }
            assertThat(refused("member", "moderation", "enforce")).isPositive();
        }

        @Test
        @DisplayName("an operating client still reaches its own duty, and a security: [] operation")
        void operatingClientsKeepTheirDuty() throws Exception {
            assertThat(status("GET", "/module/moderation/reports", Tokens.MODERATION_CLI)).isEqualTo(200);
            assertThat(status("GET", "/module/ops/plans", Tokens.BILLING_CLI)).isEqualTo(200);
            assertThat(status("GET", "/module/backoffice/journal", Tokens.BACKOFFICE_FULL)).isEqualTo(200);
            assertThat(status("POST", "/module/public/signup", Tokens.SUPER_ADMIN)).isEqualTo(200);
        }

        @Test
        @DisplayName("a mobile token is refused on a route without x-ef-surface, and on one outside the contract")
        void mobileTokenOnConsole() throws Exception {
            assertThat(status("GET", "/module/console/members", Tokens.MOBILE)).isEqualTo(403);
            assertThat(status("GET", "/module/unlisted", Tokens.MOBILE)).isEqualTo(403);
            assertThat(call("GET", "/module/me", Tokens.MOBILE).body()).isEqualTo("me");
        }

        @Test
        @DisplayName("a web token opens member and console routes, and those outside the contract")
        void webToken() throws Exception {
            assertThat(status("GET", "/module/me", Tokens.WEB)).isEqualTo(200);
            assertThat(status("GET", "/module/console/members", Tokens.WEB)).isEqualTo(200);
            assertThat(status("GET", "/module/unlisted", Tokens.WEB)).isEqualTo(200);
        }

        @Test
        @DisplayName("a security: [] operation is left alone, token or not")
        void tokenlessUntouched() throws Exception {
            assertThat(status("POST", "/module/public/signup", null)).isEqualTo(200);
            assertThat(status("POST", "/module/public/signup", Tokens.MOBILE)).isEqualTo(200);
            assertThat(status("GET", "/ws/info", Tokens.MOBILE)).isEqualTo(200);
        }

        @Test
        @DisplayName("the module's own chain still runs first: no token is 401, a bad one too")
        void chainRunsFirst() throws Exception {
            assertThat(status("GET", "/module/me", null)).isEqualTo(401);
            assertThat(status("GET", "/module/console/members", "forged")).isEqualTo(401);
        }

        @Test
        @DisplayName("the refusal is the chain's own 403, and it is counted by surface, caller and mode")
        void refusalIsCounted() throws Exception {
            double before = refused("console", "other", "enforce");

            HttpResponse<String> refused = call("GET", "/module/console/members", Tokens.MOBILE);

            assertThat(refused.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(
                    value -> assertThat(value).contains("insufficient_scope"));
            assertThat(refused("console", "other", "enforce")).isEqualTo(before + 1);
        }

        @Test
        @DisplayName("surface_mode{mode=enforce} is 1")
        void modeMetric() {
            assertThat(modeGauge("enforce")).isEqualTo(1.0);
            assertThat(modeGauge("log-only")).isNull();
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("log-only, by explicit property")
    class LogOnly extends RunningModule {

        @Override
        String[] properties() {
            return new String[]{"ecclesiaflow.security.surface.mode=log-only"};
        }

        @Test
        @DisplayName("a refusal is counted and the request goes on")
        void refusalLetThrough() throws Exception {
            double before = refused("console", "other", "log-only");

            assertThat(call("GET", "/module/console/members", Tokens.MOBILE).body()).isEqualTo("members");
            assertThat(status("GET", "/module/moderation/reports", Tokens.WEB)).isEqualTo(200);
            assertThat(refused("console", "other", "log-only")).isEqualTo(before + 1);
            assertThat(refused("platform", "console", "log-only")).isPositive();
        }

        @Test
        @DisplayName("surface_mode{mode=log-only} is 1")
        void modeMetric() {
            assertThat(modeGauge("log-only")).isEqualTo(1.0);
            assertThat(modeGauge("enforce")).isNull();
        }
    }

    @Nested
    @DisplayName("startup")
    class Startup {

        @Test
        @DisplayName("a route the contract says needs a token, open in the chain, stops the module")
        void permitAllOnATokenOperation() {
            assertThat(startupFailure("test.open=/module/me"))
                    .isInstanceOf(InvalidSurfaceConfigurationException.class)
                    .hasMessageContaining("Me.Get (GET /module/me) requires a token in the contract")
                    .hasMessageNotContaining("reaches Me.Get");
        }

        @Test
        @DisplayName("a security: [] operation the chain closes stops the module")
        void tokenlessOperationClosed() {
            assertThat(startupFailure("test.signup-open=false"))
                    .isInstanceOf(InvalidSurfaceConfigurationException.class)
                    .hasMessageContaining("Signup.Create (POST /module/public/signup) is security: []");
        }

        @Test
        @DisplayName("a served route outside the contract and the technical list, open in the chain, stops the module")
        void openRouteOutsideTheContract() {
            assertThat(startupFailure("test.open=/module/unlisted"))
                    .isInstanceOf(InvalidSurfaceConfigurationException.class)
                    .hasMessageContaining("GET /module/unlisted is open without a token");
        }

        @Test
        @DisplayName("anyRequest().permitAll() stops the module")
        void everythingOpen() {
            assertThat(startupFailure("test.everything-open=true"))
                    .isInstanceOf(InvalidSurfaceConfigurationException.class)
                    .hasMessageContaining("ConsoleMembers.List")
                    .hasMessageContaining("Reports.List");
        }

        @Test
        @DisplayName("a module without its contract does not start")
        void missingContract() {
            assertThat(startupFailure("ecclesiaflow.contract.locations=classpath:contract/absent.yaml"))
                    .isInstanceOf(InvalidApiContractException.class)
                    .hasMessageContaining("classpath:contract/absent.yaml");
        }

        @Test
        @DisplayName("a mode that is neither enforce nor log-only does not start")
        void unknownMode() {
            assertThatThrownBy(() -> boot("ecclesiaflow.security.surface.mode=off").close())
                    .hasStackTraceContaining("ecclesiaflow.security.surface.mode");
        }

        @Test
        @DisplayName("a configured list replaces the operating clients, and an emptied one keeps the default")
        void configuredOperatingClients() throws Exception {
            try (ConfigurableApplicationContext context = boot(
                    "ecclesiaflow.security.surface.operating-clients=ecclesiaflow-web")) {
                assertThat(context.getBean(SurfaceProperties.class).getOperatingClients())
                        .containsExactly("ecclesiaflow-web");
            }
            try (ConfigurableApplicationContext context = boot("ecclesiaflow.security.surface.operating-clients=")) {
                assertThat(context.getBean(SurfaceProperties.class).getOperatingClients())
                        .isEqualTo(SurfaceProperties.DEFAULT_OPERATING_CLIENTS);
            }
        }

        @Test
        @DisplayName("an empty mode keeps enforce")
        void blankModeEnforces() {
            try (ConfigurableApplicationContext context = boot("ecclesiaflow.security.surface.mode=")) {
                assertThat(context.getBean(MeterRegistry.class).find("surface.mode").tag("mode", "enforce").gauge())
                        .isNotNull();
            }
        }
    }
}
