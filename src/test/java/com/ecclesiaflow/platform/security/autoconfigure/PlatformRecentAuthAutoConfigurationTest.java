package com.ecclesiaflow.platform.security.autoconfigure;

import com.ecclesiaflow.platform.error.autoconfigure.PlatformErrorAutoConfiguration;
import com.ecclesiaflow.platform.security.reauth.PendingConfirmation;
import com.ecclesiaflow.platform.security.reauth.PendingConfirmationIssuer;
import com.ecclesiaflow.platform.security.reauth.RecentAuthAspect;
import com.ecclesiaflow.platform.security.reauth.RecentAuthExceptionHandler;
import com.ecclesiaflow.platform.security.reauth.RecentAuthPolicy;
import com.ecclesiaflow.platform.security.reauth.RecentAuthRequired;
import com.ecclesiaflow.platform.security.reauth.RecentAuthVerifier;
import com.ecclesiaflow.platform.security.reauth.SensitiveAction;
import com.ecclesiaflow.platform.security.reauth.SignInMethods;
import com.ecclesiaflow.platform.security.reauth.SignInMethodsClient;
import com.ecclesiaflow.platform.security.reauth.events.RecentAuthEventListener;
import com.ecclesiaflow.platform.time.autoconfigure.PlatformClockAutoConfiguration;
import com.ecclesiaflow.platform.web.error.autoconfigure.PlatformWebErrorAutoConfiguration;
import io.grpc.Status;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.WebApplicationContext;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The rows of ARCHITECTURE-MOBILE §3.8's table through HTTP, on a module booted the way a service boots: its own
 * beans first, the platform's auto-configurations after them.
 */
class PlatformRecentAuthAutoConfigurationTest {

    static final Instant NOW = Instant.parse("2026-10-22T08:00:00Z");
    static final String SUB = "3f2c1d4e-1111-4222-8333-944455556666";

    record EmailChange(String email) {
    }

    /** What the module's gestures did, outside the proxied controller whose fields the proxy does not share. */
    static class Gestures {
        final List<String> performed = new CopyOnWriteArrayList<>();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface RequiresConsole {
    }

    static class ConsoleOnlyException extends RuntimeException {
    }

    /** A module's own access check, as members' scope aspect is: default order, declared by the module. */
    @Aspect
    static class ModuleAccessAspect {

        @Before("@annotation(requiresConsole)")
        public void check(RequiresConsole requiresConsole) {
            throw new ConsoleOnlyException();
        }
    }

    @RestController
    static class AccountController {

        private final Gestures gestures;

        AccountController(Gestures gestures) {
            this.gestures = gestures;
        }

        @DeleteMapping("/members/me")
        @RecentAuthRequired(action = "ACCOUNT_DELETION")
        public ResponseEntity<Void> deleteMyAccount() {
            gestures.performed.add("deleted");
            return ResponseEntity.noContent().build();
        }

        @PatchMapping("/members/me/email")
        @RecentAuthRequired(action = "EMAIL_CHANGE")
        public ResponseEntity<Map<String, String>> requestEmailChange(@RequestBody EmailChange change) {
            gestures.performed.add("email change to " + change.email());
            return ResponseEntity.accepted().body(Map.of("sentTo", "NEW_ADDRESS"));
        }

        @PostMapping("/members/me/reactivate")
        @RecentAuthRequired(action = "ACCOUNT_REACTIVATION", maxAgeSeconds = 600)
        public ResponseEntity<String> reactivateMyAccount() {
            gestures.performed.add("reactivated");
            return ResponseEntity.ok("reactivated");
        }

        @DeleteMapping("/churches/me")
        @RequiresConsole
        @RecentAuthRequired(action = "CHURCH_CLOSURE")
        public ResponseEntity<Void> closeChurch() {
            gestures.performed.add("church closed");
            return ResponseEntity.noContent().build();
        }
    }

    static class StubSignInMethods implements SignInMethodsClient {

        final AtomicInteger calls = new AtomicInteger();
        volatile SignInMethods answer = new SignInMethods(true, List.of());
        volatile RuntimeException failure;

        @Override
        public SignInMethods find(String keycloakUserId) {
            calls.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return answer;
        }
    }

    static class StubIssuer implements PendingConfirmationIssuer {

        final List<SensitiveAction> issued = new CopyOnWriteArrayList<>();

        @Override
        public Object issue(SensitiveAction action) {
            issued.add(action);
            return PendingConfirmation.sentToCurrentAddress(NOW.plus(Duration.ofMinutes(15)));
        }
    }

    @RestControllerAdvice
    static class ModuleExceptionHandler {

        @ExceptionHandler(ConsoleOnlyException.class)
        ResponseEntity<Map<String, String>> consoleOnly() {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("errorCode", "INSUFFICIENT_SCOPE"));
        }
    }

    @RestControllerAdvice
    static class ModuleCatchAllHandler {

        @ExceptionHandler(Exception.class)
        ResponseEntity<Map<String, String>> any() {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("errorCode", "MODULE_CATCH_ALL"));
        }
    }

    @ImportAutoConfiguration({
            AopAutoConfiguration.class, PlatformClockAutoConfiguration.class, PlatformErrorAutoConfiguration.class,
            PlatformWebErrorAutoConfiguration.class, PlatformRecentAuthAutoConfiguration.class,
            WebMvcAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class})
    @Configuration(proxyBeanMethods = false)
    @Import({AccountController.class, ModuleAccessAspect.class, ModuleExceptionHandler.class})
    static class MembersModule {

        @Bean
        Gestures gestures() {
            return new Gestures();
        }

        @Bean
        StubSignInMethods signInMethods() {
            return new StubSignInMethods();
        }

        @Bean
        StubIssuer pendingConfirmations() {
            return new StubIssuer();
        }

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({MembersModule.class, ModuleCatchAllHandler.class})
    static class MembersModuleWithCatchAll {
    }

    private final WebApplicationContextRunner members = new WebApplicationContextRunner()
            .withUserConfiguration(MembersModule.class);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void signedIn(long secondsAgo, String identityProvider) {
        Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject(SUB)
                .issuedAt(NOW.minusSeconds(secondsAgo)).expiresAt(NOW.plusSeconds(300))
                .claim("auth_time", NOW.getEpochSecond() - secondsAgo)
                .claim("azp", "ecclesiaflow-mobile");
        if (identityProvider != null) {
            jwt.claim("identity_provider", identityProvider);
        }
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt.build(), AuthorityUtils.NO_AUTHORITIES));
    }

    private static MockMvc mvc(WebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Nested
    @DisplayName("an account without a provider link")
    class UnlinkedAccount {

        @Test
        @DisplayName("with a sign-in at most 300 s old runs the gesture")
        void recent() {
            members.run(context -> {
                signedIn(300, null);

                mvc(context).perform(delete("/members/me")).andExpect(status().isNoContent());

                assertThat(context.getBean(Gestures.class).performed).containsExactly("deleted");
            });
        }

        @Test
        @DisplayName("with an older sign-in is refused 403 REAUTH_REQUIRED, and nothing happens")
        void tooOld() {
            members.run(context -> {
                signedIn(301, null);

                mvc(context).perform(delete("/members/me"))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.status").value(403))
                        .andExpect(jsonPath("$.errorCode").value("REAUTH_REQUIRED"))
                        .andExpect(jsonPath("$.path").value("/members/me"))
                        .andExpect(jsonPath("$.timestamp").value("2026-10-22T08:00:00Z"));

                assertThat(context.getBean(Gestures.class).performed).isEmpty();
            });
        }

        @Test
        @DisplayName("with a sign-in brokered by a provider is refused, however fresh")
        void brokered() {
            members.run(context -> {
                signedIn(5, "google");

                mvc(context).perform(delete("/members/me"))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.errorCode").value("REAUTH_REQUIRED"));

                assertThat(context.getBean(Gestures.class).performed).isEmpty();
            });
        }

        @Test
        @DisplayName("gets 600 s for a reactivation")
        void reactivation() {
            members.run(context -> {
                MockMvc mvc = mvc(context);
                signedIn(600, null);
                mvc.perform(post("/members/me/reactivate")).andExpect(status().isOk());
                signedIn(601, null);
                mvc.perform(post("/members/me/reactivate"))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.errorCode").value("REAUTH_REQUIRED"));

                assertThat(context.getBean(Gestures.class).performed).containsExactly("reactivated");
            });
        }
    }

    @Nested
    @DisplayName("an account linked to a provider")
    class LinkedAccount {

        @Test
        @DisplayName("answers 202 PendingConfirmation without looking at auth_time, and the gesture waits")
        void pendingConfirmation() {
            members.run(context -> {
                context.getBean(StubSignInMethods.class).answer = new SignInMethods(false, List.of("google"));
                signedIn(86_400, "google");

                mvc(context).perform(patch("/members/me/email")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"new@example.org\"}"))
                        .andExpect(status().isAccepted())
                        .andExpect(content().json("""
                                {"status":"CONFIRMATION_SENT","sentTo":"CURRENT_ADDRESS",
                                 "expiresAt":"2026-10-22T08:15:00Z"}""", true));

                assertThat(context.getBean(Gestures.class).performed).isEmpty();
                assertThat(context.getBean(StubIssuer.class).issued).singleElement().satisfies(action -> {
                    assertThat(action.name()).isEqualTo("EMAIL_CHANGE");
                    assertThat(action.subject()).isEqualTo(SUB);
                    assertThat(action.argument(EmailChange.class)).contains(new EmailChange("new@example.org"));
                });
            });
        }
    }

    @Nested
    @DisplayName("sign-in methods that cannot be read")
    class Unavailable {

        @Test
        @DisplayName("answer 503 SERVICE_UNAVAILABLE, never a pass, even for a fresh sign-in")
        void serviceUnavailable() {
            members.run(context -> {
                context.getBean(StubSignInMethods.class).failure = Status.UNAVAILABLE.asRuntimeException();
                signedIn(1, null);

                mvc(context).perform(delete("/members/me"))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.errorCode").value("SERVICE_UNAVAILABLE"));

                assertThat(context.getBean(Gestures.class).performed).isEmpty();
                assertThat(context.getBean(StubIssuer.class).issued).isEmpty();
            });
        }
    }

    @Test
    @DisplayName("the module's own access check refuses first: auth is not asked and no link is sent")
    void moduleChecksFirst() {
        members.run(context -> {
            context.getBean(StubSignInMethods.class).answer = new SignInMethods(false, List.of("google"));
            signedIn(1, null);

            mvc(context).perform(delete("/churches/me"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_SCOPE"));

            assertThat(context.getBean(StubSignInMethods.class).calls).hasValue(0);
            assertThat(context.getBean(StubIssuer.class).issued).isEmpty();
        });
    }

    @Test
    @DisplayName("its refusals are answered ahead of a module catch-all, which would make them 500")
    void aheadOfCatchAll() {
        new WebApplicationContextRunner().withUserConfiguration(MembersModuleWithCatchAll.class).run(context -> {
            MockMvc mvc = mvc(context);
            signedIn(400, null);
            mvc.perform(delete("/members/me")).andExpect(jsonPath("$.errorCode").value("REAUTH_REQUIRED"));

            context.getBean(StubSignInMethods.class).failure = Status.UNAVAILABLE.asRuntimeException();
            mvc.perform(delete("/members/me")).andExpect(jsonPath("$.errorCode").value("SERVICE_UNAVAILABLE"));
        });
    }

    @Test
    @DisplayName("registers the policy, aspect, startup check, listener and refusal answers")
    void registered() {
        members.run(context -> {
            assertThat(context).hasSingleBean(RecentAuthPolicy.class);
            assertThat(context).hasSingleBean(RecentAuthAspect.class);
            assertThat(context).hasSingleBean(RecentAuthVerifier.class);
            assertThat(context).hasSingleBean(RecentAuthEventListener.class);
            assertThat(context).hasSingleBean(RecentAuthExceptionHandler.class);
        });
    }

    @Test
    @DisplayName("a module that guards nothing starts without a SignInMethodsClient, and has no policy")
    void nothingGuarded() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class,
                        PlatformRecentAuthAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(RecentAuthPolicy.class);
                    assertThat(context).hasSingleBean(RecentAuthAspect.class);
                });
    }

    @Test
    @DisplayName("is listed for auto-configuration, so a module gets it by depending on the library")
    void listed() {
        assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()))
                .contains(PlatformRecentAuthAutoConfiguration.class.getName());
    }

    @Test
    @DisplayName("stays away from an application that is not a servlet one")
    void notServlet() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PlatformRecentAuthAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(RecentAuthAspect.class));
    }
}
