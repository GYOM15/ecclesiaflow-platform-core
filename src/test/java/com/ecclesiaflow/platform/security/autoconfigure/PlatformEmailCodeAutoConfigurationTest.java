package com.ecclesiaflow.platform.security.autoconfigure;

import com.ecclesiaflow.platform.contract.ApiContract;
import com.ecclesiaflow.platform.contract.ApiContractLoader;
import com.ecclesiaflow.platform.error.autoconfigure.PlatformErrorAutoConfiguration;
import com.ecclesiaflow.platform.idempotency.RecordingTransactionManager;
import com.ecclesiaflow.platform.idempotency.autoconfigure.PlatformIdempotencyAutoConfiguration;
import com.ecclesiaflow.platform.security.reauth.LinkedAccountProof;
import com.ecclesiaflow.platform.security.reauth.PendingConfirmation;
import com.ecclesiaflow.platform.security.reauth.PendingConfirmationIssuer;
import com.ecclesiaflow.platform.security.reauth.RecentAuthGate;
import com.ecclesiaflow.platform.security.reauth.RecentAuthRequired;
import com.ecclesiaflow.platform.security.reauth.SensitiveAction;
import com.ecclesiaflow.platform.security.reauth.SignInMethods;
import com.ecclesiaflow.platform.security.reauth.SignInMethodsClient;
import com.ecclesiaflow.platform.security.reauth.code.ConfirmableGestures;
import com.ecclesiaflow.platform.security.reauth.code.EmailCodeMailer;
import com.ecclesiaflow.platform.security.reauth.code.EmailCodeOccurrence;
import com.ecclesiaflow.platform.security.reauth.code.EmailCodeService;
import com.ecclesiaflow.platform.security.reauth.code.EmailCodeStatus;
import com.ecclesiaflow.platform.security.reauth.code.EmailCodeStore;
import com.ecclesiaflow.platform.security.reauth.code.InMemoryEmailCodeStore;
import com.ecclesiaflow.platform.security.reauth.code.MutableClock;
import com.ecclesiaflow.platform.security.reauth.code.RecordingMailer;
import com.ecclesiaflow.platform.security.reauth.code.events.EmailCodeEventListener;
import com.ecclesiaflow.platform.security.reauth.code.events.EmailCodeMetrics;
import com.ecclesiaflow.platform.security.reauth.code.jdbc.JdbcEmailCodeStore;
import com.ecclesiaflow.platform.security.reauth.code.web.EmailCodeConfirmation;
import com.ecclesiaflow.platform.security.reauth.code.web.EmailCodeExceptionHandler;
import com.ecclesiaflow.platform.time.autoconfigure.PlatformClockAutoConfiguration;
import com.ecclesiaflow.platform.web.error.autoconfigure.PlatformWebErrorAutoConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import nl.altindag.log.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The rows of PLAN-REAUTH-CODE §3.3 and the outcomes of §4 through HTTP, on a module booted as billing boots: its
 * own beans first, the platform's auto-configurations after them.
 */
class PlatformEmailCodeAutoConfigurationTest {

    static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");
    static final String SUB = "3f2c1d4e-1111-4222-8333-944455556666";
    static final String ADDRESS = "caleb@gmail.com";
    static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    static final String CANCEL = "/billing/me/subscription/cancellation";
    static final String PURCHASE = "/billing/me/credit-packs/pack-1/purchase";

    static final String CONTRACT = """
            openapi: 3.1.1
            info: {title: billing, version: '1'}
            paths:
              /billing/me/subscription/cancellation:
                post:
                  operationId: Subscription.Cancel
                  x-ef-reauth: recent
                  x-ef-reauth-linked: email-code
                  parameters:
                    - $ref: '#/components/parameters/ReauthChallenge'
                    - $ref: '#/components/parameters/ReauthCode'
              /billing/me/credit-packs/{packId}/purchase:
                post:
                  operationId: CreditPacks.Purchase
                  x-ef-idempotency: required
                  x-ef-reauth: recent
                  x-ef-reauth-linked: email-code
                  parameters:
                    - {name: packId, in: path, required: true, schema: {type: string}}
                    - {name: Idempotency-Key, in: header, required: false, schema: {type: string, format: uuid}}
                    - $ref: '#/components/parameters/ReauthChallenge'
                    - $ref: '#/components/parameters/ReauthCode'
              /billing/me/reauth-challenges/{challengeId}/resend:
                post:
                  operationId: ReauthChallenge.Resend
                  parameters:
                    - {name: challengeId, in: path, required: true, schema: {type: string, format: uuid}}
            components:
              parameters:
                ReauthChallenge:
                  name: Reauth-Challenge
                  in: header
                  required: false
                  schema: {type: string, format: uuid}
                ReauthCode:
                  name: Reauth-Code
                  in: header
                  required: false
                  schema: {type: string, pattern: '^[0-9]{6}$'}
            """;

    static ApiContract contract(String yaml) {
        return ApiContract.of(ApiContractLoader.read(
                new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8), "billing.yaml")));
    }

    /** What the module's gestures did. */
    static class Gestures {
        final List<String> performed = new CopyOnWriteArrayList<>();
    }

    static class PackNotPurchasableException extends RuntimeException {
    }

    @RestController
    static class BillingController {

        private final Gestures gestures;
        private final RecentAuthGate gate;
        private final ObjectProvider<EmailCodeConfirmation> codes;

        BillingController(Gestures gestures, RecentAuthGate gate, ObjectProvider<EmailCodeConfirmation> codes) {
            this.gestures = gestures;
            this.gate = gate;
            this.codes = codes;
        }

        @PostMapping(CANCEL)
        @RecentAuthRequired(action = "Subscription.Cancel",
                linkedAccounts = LinkedAccountProof.SECOND_FACTOR_OR_EMAIL_CODE)
        public ResponseEntity<Map<String, String>> cancel(@RequestParam(required = false) String reason) {
            gestures.performed.add("canceled");
            return ResponseEntity.ok(Map.of("status", "CANCELING"));
        }

        // A gesture held in some of its forms only: here always, as a saved-card payment is.
        @PostMapping("/billing/me/credit-packs/{packId}/purchase")
        public ResponseEntity<Map<String, String>> purchase(@PathVariable String packId) {
            gate.require("CreditPacks.Purchase", packId);
            gestures.performed.add("purchased " + packId);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("packId", packId));
        }

        @PostMapping("/billing/me/reauth-challenges/{challengeId}/resend")
        public ResponseEntity<PendingConfirmation> resend(@PathVariable UUID challengeId) {
            return ResponseEntity.accepted().body(codes.getObject().resend(challengeId));
        }
    }

    @RestControllerAdvice
    static class BillingExceptionHandler {

        @ExceptionHandler(PackNotPurchasableException.class)
        ResponseEntity<Map<String, String>> notPurchasable() {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("errorCode", "PACK_NOT_PURCHASABLE"));
        }
    }

    static class StubSignInMethods implements SignInMethodsClient {
        volatile SignInMethods answer = new SignInMethods(false, List.of("google"), false);

        @Override
        public SignInMethods find(String keycloakUserId) {
            return answer;
        }
    }

    /** Billing's side: the church, then the request's arguments; a precheck that can refuse. */
    static class BillingGestures implements ConfirmableGestures {
        volatile boolean refuse;
        Set<String> actions = Set.of("Subscription.Cancel", "CreditPacks.Purchase");

        @Override
        public Set<String> actions() {
            return actions;
        }

        @Override
        public List<Object> boundParts(SensitiveAction action) {
            return List.of("church-1", action.arguments());
        }

        @Override
        public void precheck(SensitiveAction action) {
            if (refuse) {
                throw new PackNotPurchasableException();
            }
        }

        @Override
        public String emailLabel(SensitiveAction action) {
            return action.name().equals("Subscription.Cancel") ? "billing.cancel" : "billing.savedCardPayment";
        }
    }

    /** What billing declares besides the ports, which each test gives or withholds. */
    @ImportAutoConfiguration({
            AopAutoConfiguration.class, PlatformClockAutoConfiguration.class, PlatformErrorAutoConfiguration.class,
            PlatformWebErrorAutoConfiguration.class, PlatformRecentAuthAutoConfiguration.class,
            PlatformIdempotencyAutoConfiguration.class, PlatformEmailCodeAutoConfiguration.class,
            WebMvcAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class})
    @Configuration(proxyBeanMethods = false)
    @Import({BillingController.class, BillingExceptionHandler.class})
    static class BillingCore {

        @Bean
        Gestures gestures() {
            return new Gestures();
        }

        @Bean
        StubSignInMethods signInMethods() {
            return new StubSignInMethods();
        }

        @Bean
        MutableClock clock() {
            return new MutableClock(NOW);
        }
    }

    private final WebApplicationContextRunner core = new WebApplicationContextRunner()
            .withUserConfiguration(BillingCore.class)
            .withPropertyValues("ecclesiaflow.security.reauth.email-code.enabled=true",
                    "ecclesiaflow.security.reauth.email-code.key=" + KEY);

    private WebApplicationContextRunner withPorts(WebApplicationContextRunner runner, boolean mailer,
                                                  boolean gestures, boolean transactions, boolean store) {
        WebApplicationContextRunner wired = runner;
        if (mailer) {
            wired = wired.withBean(RecordingMailer.class, RecordingMailer::new);
        }
        if (gestures) {
            wired = wired.withBean(BillingGestures.class, BillingGestures::new);
        }
        if (transactions) {
            wired = wired.withBean(PlatformTransactionManager.class, RecordingTransactionManager::new);
        }
        if (store) {
            wired = wired.withBean(InMemoryEmailCodeStore.class, InMemoryEmailCodeStore::new);
        }
        return wired;
    }

    private final WebApplicationContextRunner billing =
            withPorts(core.withBean(ApiContract.class, () -> contract(CONTRACT)), true, true, true, true);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void signedIn(long secondsAgo, List<String> amr) {
        Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject(SUB)
                .issuedAt(NOW.minusSeconds(secondsAgo)).expiresAt(NOW.plusSeconds(300))
                .claim("auth_time", NOW.getEpochSecond() - secondsAgo)
                .claim("identity_provider", "google")
                .claim("email", ADDRESS)
                .claim("locale", "fr");
        if (amr != null) {
            jwt.claim("amr", amr);
        }
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt.build(), AuthorityUtils.NO_AUTHORITIES));
    }

    private static MockMvc mvc(WebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return new ObjectMapper().readTree(result.getResponse().getContentAsString());
    }

    private static MockHttpServletRequestBuilder withCode(MockHttpServletRequestBuilder request, String challengeId,
                                                          String code) {
        return request.header("Reauth-Challenge", challengeId).header("Reauth-Code", code);
    }

    private static String wrong(String code) {
        return code.equals("000000") ? "000001" : "000000";
    }

    @Nested
    @DisplayName("a linked account without an authenticator app")
    class WithoutApp {

        @Test
        @DisplayName("gets 202 CODE_SENT, then runs the gesture once on the replay with its code, then 410")
        void codeThenGesture() {
            billing.run(context -> {
                MockMvc mvc = mvc(context);
                signedIn(86_400, null);
                RecordingMailer mailer = context.getBean(RecordingMailer.class);

                JsonNode pending = json(mvc.perform(post(CANCEL))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.status").value("CODE_SENT"))
                        .andExpect(jsonPath("$.sentTo").value("CURRENT_ADDRESS"))
                        .andExpect(jsonPath("$.channel").value("EMAIL"))
                        .andExpect(jsonPath("$.maskedAddress").value("c•••@gmail.com"))
                        .andExpect(jsonPath("$.expiresAt").value("2026-11-14T10:10:00Z"))
                        .andExpect(jsonPath("$.resendAvailableAt").value("2026-11-14T10:01:00Z"))
                        .andReturn());
                String challengeId = pending.get("challengeId").asText();

                assertThat(context.getBean(Gestures.class).performed).isEmpty();
                assertThat(mailer.sent).singleElement().satisfies(delivery -> {
                    assertThat(delivery.address()).isEqualTo(ADDRESS);
                    assertThat(delivery.gesture()).isEqualTo("billing.cancel");
                });

                mvc.perform(withCode(post(CANCEL), challengeId, mailer.lastCode()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("CANCELING"));
                mvc.perform(withCode(post(CANCEL), challengeId, mailer.lastCode()))
                        .andExpect(status().isGone())
                        .andExpect(jsonPath("$.errorCode").value("REAUTH_CODE_EXPIRED"));

                assertThat(context.getBean(Gestures.class).performed).containsExactly("canceled");
            });
        }

        @Test
        @DisplayName("is held the same when auth predates has_authenticator")
        void olderAuth() {
            billing.run(context -> {
                context.getBean(StubSignInMethods.class).answer = new SignInMethods(false, List.of("google"));
                signedIn(10, null);

                mvc(context).perform(post(CANCEL))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.status").value("CODE_SENT"));
            });
        }

        @Test
        @DisplayName("answers a wrong code 400 counted, malformed headers 400 uncounted, the gesture never run")
        void wrongAndMalformed() {
            billing.run(context -> {
                MockMvc mvc = mvc(context);
                signedIn(10, null);
                String challengeId = json(mvc.perform(post(CANCEL)).andReturn()).get("challengeId").asText();
                String code = context.getBean(RecordingMailer.class).lastCode();

                mvc.perform(withCode(post(CANCEL), challengeId, wrong(code)))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.errorCode").value("REAUTH_CODE_INVALID"));
                mvc.perform(withCode(post(CANCEL), challengeId, "12"))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.errorCode").value("REAUTH_CODE_INVALID"));
                mvc.perform(post(CANCEL).header("Reauth-Code", code))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.errorCode").value("REAUTH_CODE_INVALID"));

                assertThat(context.getBean(InMemoryEmailCodeStore.class).count(SUB, EmailCodeOccurrence.FAILED))
                        .isEqualTo(1);
                assertThat(context.getBean(Gestures.class).performed).isEmpty();
            });
        }

        @Test
        @DisplayName("answers 410 to the code presented with another request, which then replaces its challenge")
        void boundToRequest() {
            billing.run(context -> {
                MockMvc mvc = mvc(context);
                signedIn(10, null);
                String challengeId = json(mvc.perform(post(CANCEL).param("reason", "too-expensive")).andReturn())
                        .get("challengeId").asText();
                String code = context.getBean(RecordingMailer.class).lastCode();

                mvc.perform(withCode(post(CANCEL).param("reason", "other"), challengeId, code))
                        .andExpect(status().isGone())
                        .andExpect(jsonPath("$.errorCode").value("REAUTH_CODE_EXPIRED"));

                assertThat(context.getBean(InMemoryEmailCodeStore.class).get(UUID.fromString(challengeId)).status())
                        .isEqualTo(EmailCodeStatus.SUPERSEDED);
                assertThat(context.getBean(Gestures.class).performed).isEmpty();
            });
        }

        @Test
        @DisplayName("answers 429 REAUTH_CODE_LOCKED with Retry-After after ten wrong codes, nothing mailed")
        void locked() {
            billing.run(context -> {
                InMemoryEmailCodeStore store = context.getBean(InMemoryEmailCodeStore.class);
                for (int i = 0; i < 10; i++) {
                    store.record(SUB, EmailCodeOccurrence.FAILED, UUID.randomUUID(), NOW);
                }
                signedIn(10, null);

                mvc(context).perform(post(CANCEL))
                        .andExpect(status().isTooManyRequests())
                        .andExpect(header().string("Retry-After", "86400"))
                        .andExpect(jsonPath("$.errorCode").value("REAUTH_CODE_LOCKED"));

                assertThat(context.getBean(RecordingMailer.class).sent).isEmpty();
            });
        }

        @Test
        @DisplayName("answers 503 when the mail is refused, the challenge voided")
        void mailRefused() {
            billing.run(context -> {
                context.getBean(RecordingMailer.class).failure = new IllegalStateException("communication down");
                signedIn(10, null);

                mvc(context).perform(post(CANCEL))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.errorCode").value("SERVICE_UNAVAILABLE"));

                assertThat(context.getBean(InMemoryEmailCodeStore.class).only().status())
                        .isEqualTo(EmailCodeStatus.VOID);
            });
        }

        @Test
        @DisplayName("mails nothing when the gesture's own checks refuse it, which answers its own code")
        void precheckRefuses() {
            billing.run(context -> {
                context.getBean(BillingGestures.class).refuse = true;
                signedIn(10, null);

                mvc(context).perform(post(PURCHASE).header("Idempotency-Key", UUID.randomUUID().toString()))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.errorCode").value("PACK_NOT_PURCHASABLE"));

                assertThat(context.getBean(RecordingMailer.class).sent).isEmpty();
            });
        }

        @Test
        @DisplayName("holds a gesture checked in its delegate with the same 202, bound to its Idempotency-Key")
        void conditionalGesture() {
            billing.run(context -> {
                MockMvc mvc = mvc(context);
                signedIn(10, null);
                String key = UUID.randomUUID().toString();
                RecordingMailer mailer = context.getBean(RecordingMailer.class);

                String challengeId = json(mvc.perform(post(PURCHASE).header("Idempotency-Key", key))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.status").value("CODE_SENT"))
                        .andReturn()).get("challengeId").asText();
                assertThat(context.getBean(Gestures.class).performed).isEmpty();
                assertThat(mailer.sent).singleElement().extracting(delivery -> delivery.gesture())
                        .isEqualTo("billing.savedCardPayment");

                mvc.perform(withCode(post(PURCHASE).header("Idempotency-Key", key), challengeId, mailer.lastCode()))
                        .andExpect(status().isCreated());
                mvc.perform(withCode(post(PURCHASE).header("Idempotency-Key", key), challengeId, mailer.lastCode()))
                        .andExpect(status().isCreated());
                mvc.perform(withCode(post(PURCHASE).header("Idempotency-Key", UUID.randomUUID().toString()),
                                challengeId, mailer.lastCode()))
                        .andExpect(status().isGone());

                assertThat(context.getBean(Gestures.class).performed).containsExactly("purchased pack-1",
                        "purchased pack-1");
            });
        }

        @Test
        @DisplayName("resends a code once 60 s have passed, answering 429 RATE_LIMIT_EXCEEDED before")
        void resend() {
            billing.run(context -> {
                MockMvc mvc = mvc(context);
                signedIn(10, null);
                String challengeId = json(mvc.perform(post(CANCEL)).andReturn()).get("challengeId").asText();
                String resend = "/billing/me/reauth-challenges/" + challengeId + "/resend";

                mvc.perform(post(resend))
                        .andExpect(status().isTooManyRequests())
                        .andExpect(header().string("Retry-After", "60"))
                        .andExpect(jsonPath("$.errorCode").value("RATE_LIMIT_EXCEEDED"));
                context.getBean(MutableClock.class).advance(Duration.ofSeconds(60));
                mvc.perform(post(resend))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.challengeId").value(challengeId))
                        .andExpect(jsonPath("$.expiresAt").value("2026-11-14T10:11:00Z"));
                mvc.perform(post("/billing/me/reauth-challenges/" + UUID.randomUUID() + "/resend"))
                        .andExpect(status().isGone())
                        .andExpect(jsonPath("$.errorCode").value("REAUTH_CODE_EXPIRED"));

                assertThat(context.getBean(RecordingMailer.class).sent).hasSize(2);
            });
        }

        @Test
        @DisplayName("writes neither the code, the address, a digest nor the headers in any log line")
        void logs() {
            try (LogCaptor logs = LogCaptor.forRoot()) {
                logs.setLogLevelToInfo();
                billing.run(context -> {
                    MockMvc mvc = mvc(context);
                    signedIn(10, null);
                    String challengeId = json(mvc.perform(post(CANCEL)).andReturn()).get("challengeId").asText();
                    String code = context.getBean(RecordingMailer.class).lastCode();
                    mvc.perform(withCode(post(CANCEL), challengeId, wrong(code)));
                    mvc.perform(withCode(post(CANCEL), challengeId, code)).andExpect(status().isOk());
                    InMemoryEmailCodeStore store = context.getBean(InMemoryEmailCodeStore.class);
                    String codeDigest = store.only().codeDigest();
                    String bindingDigest = store.only().bindingDigest();

                    assertThat(logs.getLogs()).anyMatch(line -> line.contains("REAUTH-CODE: ✅ Code accepted"));
                    assertThat(String.join("\n", logs.getLogs()))
                            .doesNotContain(ADDRESS, challengeId, codeDigest, bindingDigest, SUB, "Reauth-Code",
                                    "Reauth-Challenge")
                            .doesNotContainPattern("(?<![0-9])" + code + "(?![0-9])");
                });
                logs.resetLogLevel();
            }
        }
    }

    @Nested
    @DisplayName("a linked account with an authenticator app")
    class WithApp {

        @Test
        @DisplayName("is sent to sign in again without a recent second factor, and nothing is mailed")
        void signInAgain() {
            billing.run(context -> {
                context.getBean(StubSignInMethods.class).answer = new SignInMethods(false, List.of("google"), true);
                signedIn(10, List.of("pwd"));

                mvc(context).perform(post(CANCEL))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.errorCode").value("REAUTH_REQUIRED"));

                assertThat(context.getBean(RecordingMailer.class).sent).isEmpty();
                assertThat(context.getBean(Gestures.class).performed).isEmpty();
            });
        }

        @Test
        @DisplayName("runs the gesture on an otp attested within 300 s, with no code at all")
        void attested() {
            billing.run(context -> {
                context.getBean(StubSignInMethods.class).answer = new SignInMethods(false, List.of("google"), true);
                signedIn(30, List.of("pwd", "otp"));

                mvc(context).perform(post(CANCEL)).andExpect(status().isOk());
                mvc(context).perform(post(PURCHASE)).andExpect(status().isCreated());

                assertThat(context.getBean(Gestures.class).performed).containsExactly("canceled", "purchased pack-1");
                assertThat(context.getBean(RecordingMailer.class).sent).isEmpty();
            });
        }
    }

    @Test
    @DisplayName("registers the code's beans, and needs no PendingConfirmationIssuer")
    void registered() {
        billing.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(PendingConfirmationIssuer.class);
            assertThat(context).hasSingleBean(EmailCodeService.class);
            assertThat(context).hasSingleBean(EmailCodeConfirmation.class);
            assertThat(context).hasSingleBean(EmailCodeExceptionHandler.class);
            assertThat(context).hasSingleBean(EmailCodeEventListener.class);
            assertThat(context).hasSingleBean(EmailCodeMetrics.class);
            assertThat(context.getBean(EmailCodeStore.class)).isInstanceOf(InMemoryEmailCodeStore.class);
        });
    }

    @Test
    @DisplayName("is listed for auto-configuration")
    void listed() {
        assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()))
                .contains(PlatformEmailCodeAutoConfiguration.class.getName());
    }

    /** A module that does not enable the code: members and church, as they are. */
    @Nested
    @DisplayName("left disabled")
    class Disabled {

        @Test
        @DisplayName("registers nothing, so members' and church's registries gain no code")
        void nothing() {
            new WebApplicationContextRunner()
                    .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                            AopAutoConfiguration.class, PlatformRecentAuthAutoConfiguration.class,
                            PlatformEmailCodeAutoConfiguration.class))
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).doesNotHaveBean(EmailCodeExceptionHandler.class);
                        assertThat(context).doesNotHaveBean(EmailCodeConfirmation.class);
                        assertThat(context).doesNotHaveBean(EmailCodeService.class);
                    });
        }

        @Test
        @DisplayName("refuses to start a module whose gestures are confirmed by code")
        void codeGestureWithoutCode() {
            billing.withPropertyValues("ecclesiaflow.security.reauth.email-code.enabled=false").run(context ->
                    assertThat(context).getFailure()
                            .hasMessageContaining("no e-mail code confirms Subscription.Cancel"));
        }

        @Test
        @DisplayName("stays away from an application that is not a servlet one")
        void notServlet() {
            new ApplicationContextRunner()
                    .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                            PlatformEmailCodeAutoConfiguration.class))
                    .withPropertyValues("ecclesiaflow.security.reauth.email-code.enabled=true")
                    .run(context -> assertThat(context).doesNotHaveBean(EmailCodeService.class));
        }
    }

    @Nested
    @DisplayName("enabled, refuses to start")
    class StartupRefusals {

        @Test
        @DisplayName("without a key, or with one under 32 bytes, never quoting it")
        void key() {
            billing.withPropertyValues("ecclesiaflow.security.reauth.email-code.key=").run(context ->
                    assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("ecclesiaflow.security.reauth.email-code.key is required"));
            String shortKey = Base64.getEncoder().encodeToString(new byte[31]);
            billing.withPropertyValues("ecclesiaflow.security.reauth.email-code.key=" + shortKey).run(context ->
                    assertThat(context).getFailure().rootCause().hasMessageContaining("must be 32 random bytes or more")
                            .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain(shortKey)));
        }

        @Test
        @DisplayName("without an EmailCodeMailer")
        void noMailer() {
            withPorts(core.withBean(ApiContract.class, () -> contract(CONTRACT)), false, true, true, true)
                    .run(context -> assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("lacks an EmailCodeMailer"));
        }

        @Test
        @DisplayName("without ConfirmableGestures")
        void noGestures() {
            withPorts(core.withBean(ApiContract.class, () -> contract(CONTRACT)), true, false, true, true)
                    .run(context -> assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("lacks a ConfirmableGestures"));
        }

        @Test
        @DisplayName("without a single transaction manager")
        void noTransactionManager() {
            withPorts(core.withBean(ApiContract.class, () -> contract(CONTRACT)), true, true, false, true)
                    .run(context -> assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("lacks a single PlatformTransactionManager"));
        }

        @Test
        @DisplayName("when its gestures and its contract's marks differ, either way")
        void contractDiffers() {
            withPorts(core.withBean(ApiContract.class, () -> contract(CONTRACT)), true, false, true, true)
                    .withBean(BillingGestures.class, () -> {
                        BillingGestures gestures = new BillingGestures();
                        gestures.actions = Set.of("Subscription.Cancel", "PaymentMethod.Remove");
                        return gestures;
                    })
                    .run(context -> assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("not marked in the contract: [PaymentMethod.Remove]")
                            .hasMessageContaining("unknown to ConfirmableGestures: [CreditPacks.Purchase]"));
        }

        @Test
        @DisplayName("when its contract misdeclares a mark")
        void misdeclared() {
            String required = CONTRACT.replace("required: false\n      schema: {type: string, format: uuid}",
                    "required: true\n      schema: {type: string, format: uuid}");
            assertThat(required).isNotEqualTo(CONTRACT);

            withPorts(core.withBean(ApiContract.class, () -> contract(required)), true, true, true, true)
                    .run(context -> assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("x-ef-reauth-linked")
                            .hasMessageContaining("found it required"));
        }

        @Test
        @DisplayName("without a DataSource when it keeps the JDBC store, which it takes otherwise")
        void jdbcStore() {
            WebApplicationContextRunner withoutStore =
                    withPorts(core.withBean(ApiContract.class, () -> contract(CONTRACT)), true, true, true, false);

            withoutStore.run(context -> assertThat(context).getFailure().rootCause()
                    .hasMessageContaining("lacks a single DataSource"));
            withoutStore.withBean(DataSource.class, () -> new DriverManagerDataSource("jdbc:postgresql://nowhere/x"))
                    .run(context -> assertThat(context.getBean(EmailCodeStore.class))
                            .isInstanceOf(JdbcEmailCodeStore.class));
        }
    }
}
