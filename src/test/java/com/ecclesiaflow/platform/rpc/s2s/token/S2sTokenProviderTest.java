package com.ecclesiaflow.platform.rpc.s2s.token;

import com.ecclesiaflow.platform.rpc.s2s.S2sProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

/**
 * Exercises the token pipeline (client + cache + provider) against a real HTTP endpoint
 * hosted by MockWebServer, or against a scripted client when the timing of the exchange
 * itself is what is under test.
 */
class S2sTokenProviderTest {

    private static final Instant T0 = Instant.parse("2026-10-02T10:00:00Z");

    private MockWebServer keycloak;
    private S2sProperties props;
    private MutableClock clock;
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

        clock = new MutableClock(T0);
        provider = new S2sTokenProvider(
                new S2sTokenClient(props, HttpClient.newHttpClient(), clock), new S2sTokenCache(clock), props, clock);
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
    void anExpiredTokenIsReplacedBeforeUse() {
        keycloak.enqueue(jsonResponse(200, "{\"access_token\":\"jwt-old\",\"expires_in\":300}"));
        keycloak.enqueue(jsonResponse(200, "{\"access_token\":\"jwt-fresh\",\"expires_in\":300}"));

        assertThat(provider.getToken()).isEqualTo("jwt-old");
        clock.advance(Duration.ofSeconds(301));

        assertThat(provider.getToken()).isEqualTo("jwt-fresh");
        assertThat(keycloak.getRequestCount()).isEqualTo(2);
    }

    @Test
    void insideTheRefreshWindowTheCurrentTokenIsServedWhileOneRefreshRuns() {
        keycloak.enqueue(jsonResponse(200, "{\"access_token\":\"jwt-old\",\"expires_in\":300}"));
        keycloak.enqueue(jsonResponse(200, "{\"access_token\":\"jwt-fresh\",\"expires_in\":300}"));
        assertThat(provider.getToken()).isEqualTo("jwt-old");

        clock.advance(Duration.ofSeconds(297)); // 3 s left, inside the 5 s leeway

        assertThat(provider.getToken()).isEqualTo("jwt-old");
        await().atMost(Duration.ofSeconds(5)).until(() -> provider.getToken().equals("jwt-fresh"));
        assertThat(keycloak.getRequestCount()).isEqualTo(2);
    }

    @Test
    void aFailedBackgroundRefreshKeepsServingTheValidToken() {
        AtomicInteger calls = new AtomicInteger();
        ScriptedTokenClient flaky = new ScriptedTokenClient(props, () -> {
            if (calls.incrementAndGet() == 1) {
                return new S2sToken("jwt-1", clock.instant().plusSeconds(300));
            }
            throw new S2sTokenException("keycloak down");
        });
        S2sTokenProvider inline = new S2sTokenProvider(flaky, new S2sTokenCache(clock), props, clock, Runnable::run);
        assertThat(inline.getToken()).isEqualTo("jwt-1");

        clock.advance(Duration.ofSeconds(297));

        assertThat(inline.getToken()).isEqualTo("jwt-1");
        assertThat(inline.getToken()).isEqualTo("jwt-1");
        assertThat(flaky.fetches.get()).as("no new attempt during the backoff").isEqualTo(2);
    }

    @Test
    void aTokenShorterThanTheLeewayIsStillReused() {
        // A realm that issues 20 s service-account tokens against a 30 s leeway used to
        // make every single call fetch a new token.
        props.setRefreshLeewaySeconds(30);
        S2sTokenProvider shortLived = new S2sTokenProvider(
                new S2sTokenClient(props, HttpClient.newHttpClient(), clock), new S2sTokenCache(clock), props, clock);
        keycloak.enqueue(jsonResponse(200, "{\"access_token\":\"jwt-1\",\"expires_in\":20}"));
        keycloak.enqueue(jsonResponse(200, "{\"access_token\":\"jwt-2\",\"expires_in\":20}"));

        assertThat(shortLived.getToken()).isEqualTo("jwt-1");
        assertThat(shortLived.getToken()).isEqualTo("jwt-1");
        assertThat(keycloak.getRequestCount()).isEqualTo(1);
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
    void concurrentCallsResultInSingleFetch() throws Exception {
        keycloak.enqueue(jsonResponse(200, "{\"access_token\":\"jwt-1\",\"expires_in\":300}"));

        List<String> tokens = concurrently(16, provider::getToken);

        assertThat(tokens).hasSize(16).containsOnly("jwt-1");
        assertThat(keycloak.getRequestCount()).isEqualTo(1);
    }

    @Test
    void concurrentCallersShareOneFailedFetch() throws Exception {
        ScriptedTokenClient keycloakDown = new ScriptedTokenClient(props, () -> {
            sleep(300);
            throw new S2sTokenException("keycloak down");
        });
        S2sTokenProvider failing = new S2sTokenProvider(keycloakDown, new S2sTokenCache(clock), props, clock);

        List<Throwable> outcomes = concurrently(5, () -> catchThrowable(failing::getToken));

        assertThat(outcomes).hasSize(5).allSatisfy(t -> assertThat(t).isInstanceOf(S2sTokenException.class));
        // Each waiter used to find the cache still empty and run its own 10 s exchange in turn.
        assertThat(keycloakDown.fetches.get()).isEqualTo(1);
    }

    @Test
    void aFailedFetchIsNotRepeatedByTheNextCallerStraightAway() {
        ScriptedTokenClient keycloakDown = new ScriptedTokenClient(props, () -> {
            throw new S2sTokenException("keycloak down");
        });
        S2sTokenProvider failing = new S2sTokenProvider(keycloakDown, new S2sTokenCache(clock), props, clock);

        assertThatThrownBy(failing::getToken).isInstanceOf(S2sTokenException.class);
        assertThatThrownBy(failing::getToken)
                .isInstanceOf(S2sTokenException.class)
                .hasCauseInstanceOf(S2sTokenException.class);

        assertThat(keycloakDown.fetches.get()).isEqualTo(1);
    }

    @Test
    void theBackoffDoublesAndThenAllowsANewAttempt() {
        ScriptedTokenClient keycloakDown = new ScriptedTokenClient(props, () -> {
            throw new S2sTokenException("keycloak down");
        });
        S2sTokenProvider failing = new S2sTokenProvider(
                keycloakDown, new S2sTokenCache(clock), props, clock, Runnable::run);

        assertThatThrownBy(failing::getToken).isInstanceOf(S2sTokenException.class);
        clock.advance(Duration.ofMillis(1_001));
        assertThatThrownBy(failing::getToken).isInstanceOf(S2sTokenException.class);
        assertThat(keycloakDown.fetches.get()).isEqualTo(2);

        clock.advance(Duration.ofMillis(1_500)); // second backoff is 2 s
        assertThatThrownBy(failing::getToken).isInstanceOf(S2sTokenException.class);
        assertThat(keycloakDown.fetches.get()).isEqualTo(2);

        clock.advance(Duration.ofMillis(600));
        assertThatThrownBy(failing::getToken).isInstanceOf(S2sTokenException.class);
        assertThat(keycloakDown.fetches.get()).isEqualTo(3);
    }

    @Test
    void aSuccessfulFetchClearsTheBackoff() {
        AtomicInteger calls = new AtomicInteger();
        ScriptedTokenClient recovering = new ScriptedTokenClient(props, () -> {
            if (calls.incrementAndGet() == 1) {
                throw new S2sTokenException("keycloak down");
            }
            return new S2sToken("jwt-" + calls.get(), clock.instant().plusSeconds(300));
        });
        S2sTokenProvider inline = new S2sTokenProvider(recovering, new S2sTokenCache(clock), props, clock, Runnable::run);

        assertThatThrownBy(inline::getToken).isInstanceOf(S2sTokenException.class);
        clock.advance(Duration.ofMillis(1_001));
        assertThat(inline.getToken()).isEqualTo("jwt-2");

        inline.invalidate();
        assertThat(inline.getToken()).as("no backoff left after a success").isEqualTo("jwt-3");
    }

    @Test
    void aCallerGivesUpAtItsBoundWithoutCancellingTheSharedRefresh() {
        CountDownLatch keycloakAnswers = new CountDownLatch(1);
        ScriptedTokenClient slow = new ScriptedTokenClient(props, () -> {
            try {
                keycloakAnswers.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new S2sToken("jwt-late", clock.instant().plusSeconds(300));
        });
        S2sTokenProvider waiting = new S2sTokenProvider(slow, new S2sTokenCache(clock), props, clock);

        long started = System.nanoTime();
        assertThatThrownBy(() -> waiting.getToken(Duration.ofMillis(100)))
                .isInstanceOf(S2sTokenException.class)
                .hasMessageContaining("100 ms");
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1_000);

        keycloakAnswers.countDown();
        assertThat(waiting.getToken()).isEqualTo("jwt-late");
        assertThat(slow.fetches.get()).isEqualTo(1);
    }

    @Test
    void aTokenThatLandsJustBeforeTheRefreshStartsIsUsedWithoutAnotherFetch() {
        S2sToken landed = new S2sToken("jwt-landed", clock.instant().plusSeconds(300));
        AtomicInteger reads = new AtomicInteger();
        S2sTokenCache racing = new S2sTokenCache(clock) {
            @Override
            public Optional<S2sToken> getIfValid(int leewaySeconds) {
                return reads.incrementAndGet() <= 2 ? Optional.empty() : Optional.of(landed);
            }
        };
        ScriptedTokenClient counting = new ScriptedTokenClient(props, () -> landed);
        S2sTokenProvider raced = new S2sTokenProvider(counting, racing, props, clock, Runnable::run);

        assertThat(raced.getToken()).isEqualTo("jwt-landed");
        assertThat(counting.fetches.get()).isZero();
    }

    @Test
    void invalidateMakesTheNextCallFetchAFreshToken() {
        keycloak.enqueue(jsonResponse(200, "{\"access_token\":\"jwt-revoked\",\"expires_in\":300}"));
        keycloak.enqueue(jsonResponse(200, "{\"access_token\":\"jwt-new\",\"expires_in\":300}"));
        assertThat(provider.getToken()).isEqualTo("jwt-revoked");

        provider.invalidate();

        assertThat(provider.getToken()).isEqualTo("jwt-new");
    }

    @Test
    void aRefreshThatCannotBeScheduledFailsTheCaller() {
        S2sTokenProvider unschedulable = new S2sTokenProvider(
                new ScriptedTokenClient(props, () -> new S2sToken("never", clock.instant().plusSeconds(300))),
                new S2sTokenCache(clock), props, clock,
                task -> {
                    throw new RejectedExecutionException("shutting down");
                });

        assertThatThrownBy(unschedulable::getToken)
                .isInstanceOf(S2sTokenException.class)
                .hasMessageContaining("Could not start");
    }

    @Test
    void anUnexpectedClientFailureIsReportedAsATokenFailure() {
        S2sTokenProvider broken = new S2sTokenProvider(
                new ScriptedTokenClient(props, () -> {
                    throw new IllegalStateException("bug");
                }),
                new S2sTokenCache(clock), props, clock, Runnable::run);

        assertThatThrownBy(broken::getToken)
                .isInstanceOf(S2sTokenException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void anInterruptedCallerStopsWaiting() throws Exception {
        CountDownLatch keycloakAnswers = new CountDownLatch(1);
        S2sTokenProvider waiting = new S2sTokenProvider(
                new ScriptedTokenClient(props, () -> {
                    try {
                        keycloakAnswers.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return new S2sToken("jwt", clock.instant().plusSeconds(300));
                }),
                new S2sTokenCache(clock), props, clock);
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(waiting::getToken)
                    .isInstanceOf(S2sTokenException.class)
                    .hasMessageContaining("Interrupted");
            assertThat(Thread.interrupted()).isTrue();
        } finally {
            keycloakAnswers.countDown();
        }
    }

    private static <T> List<T> concurrently(int callers, Callable<T> call) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static MockResponse jsonResponse(int status, String body) {
        return new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    static final class ScriptedTokenClient extends S2sTokenClient {

        final AtomicInteger fetches = new AtomicInteger();
        private final Supplier<S2sToken> script;

        ScriptedTokenClient(S2sProperties props, Supplier<S2sToken> script) {
            super(props);
            this.script = script;
        }

        @Override
        public S2sToken fetchToken() {
            fetches.incrementAndGet();
            return script.get();
        }
    }

    static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
