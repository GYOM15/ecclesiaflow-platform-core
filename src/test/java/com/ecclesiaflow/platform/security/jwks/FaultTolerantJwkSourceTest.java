package com.ecclesiaflow.platform.security.jwks;

import com.ecclesiaflow.platform.security.RestJwtProperties;
import com.ecclesiaflow.platform.security.jwks.events.JwksEvents;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.RemoteKeySourceException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.Resource;
import com.nimbusds.jose.util.ResourceRetriever;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URL;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FaultTolerantJwkSourceTest {

    private static final Instant START = Instant.parse("2026-10-22T08:00:00Z");

    private static RSAKey keyA;
    private static RSAKey keyB;

    private final MutableClock clock = new MutableClock(START);
    private final StubKeycloak keycloak = new StubKeycloak();
    private final List<Object> events = new CopyOnWriteArrayList<>();
    private final FaultTolerantJwkSource source = new FaultTolerantJwkSource(
            url(), keycloak, new RestJwtProperties.Jwks(), clock, events::add);

    @BeforeAll
    static void generateKeys() throws JOSEException {
        keyA = new RSAKeyGenerator(2048).keyID("key-a").generate();
        keyB = new RSAKeyGenerator(2048).keyID("key-b").generate();
    }

    private List<JWK> keysFor(String kid) throws KeySourceException {
        return source.get(new JWKSelector(new JWKMatcher.Builder().keyID(kid).build()), null);
    }

    private <T> List<T> eventsOf(Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }

    @Nested
    @DisplayName("the first load")
    class FirstLoad {

        @Test
        @DisplayName("happens on first use, and the set is then served from memory until the refresh interval")
        void servedFromMemory() throws KeySourceException {
            keycloak.serve(keyA);

            assertThat(keysFor("key-a")).singleElement().extracting(JWK::getKeyID).isEqualTo("key-a");
            clock.advance(Duration.ofMinutes(5).minusSeconds(1));
            assertThat(keysFor("key-a")).hasSize(1);

            assertThat(keycloak.calls()).isEqualTo(1);
            assertThat(eventsOf(JwksEvents.KeySetLoaded.class))
                    .containsExactly(new JwksEvents.KeySetLoaded(JwksEvents.Trigger.FIRST_LOAD, 1, false));
        }

        @Test
        @DisplayName("that fails is an infrastructure failure, retried by the next request without waiting")
        void failedFirstLoad() throws KeySourceException {
            keycloak.failWith(new ConnectException("Connection refused"));

            assertThatThrownBy(() -> keysFor("key-a"))
                    .isInstanceOf(RemoteKeySourceException.class)
                    .hasMessage("The JWK set could not be loaded")
                    .hasCauseInstanceOf(ConnectException.class);
            assertThat(eventsOf(JwksEvents.ReloadFailed.class)).singleElement().satisfies(failed -> {
                assertThat(failed.trigger()).isEqualTo(JwksEvents.Trigger.FIRST_LOAD);
                assertThat(failed.outage()).isNull();
                assertThat(failed.servingLastKnown()).isFalse();
            });

            keycloak.serve(keyA);
            assertThat(keysFor("key-a")).hasSize(1);
            assertThat(keycloak.calls()).isEqualTo(2);
            assertThat(eventsOf(JwksEvents.KeySetLoaded.class))
                    .containsExactly(new JwksEvents.KeySetLoaded(JwksEvents.Trigger.FIRST_LOAD, 1, true));
        }
    }

    @Nested
    @DisplayName("a scheduled refresh")
    class Refresh {

        @Test
        @DisplayName("replaces the set: a key the realm withdrew is no longer trusted")
        void replacesTheSet() throws KeySourceException {
            keycloak.serve(keyA);
            keysFor("key-a");

            keycloak.serve(keyB);
            clock.advance(Duration.ofMinutes(5));

            assertThat(keysFor("key-b")).hasSize(1);
            assertThat(keysFor("key-a")).isEmpty();
            assertThat(keycloak.calls()).isEqualTo(2);
            assertThat(eventsOf(JwksEvents.KeySetLoaded.class)).last()
                    .isEqualTo(new JwksEvents.KeySetLoaded(JwksEvents.Trigger.REFRESH, 1, false));
        }
    }

    @Nested
    @DisplayName("an outage after a first load")
    class Outage {

        @Test
        @DisplayName("keeps decoding a known kid past five minutes: the last set still serves")
        void lastKnownServed() throws KeySourceException {
            keycloak.serve(keyA);
            keysFor("key-a");

            keycloak.failWith(new ConnectException("Connection refused"));
            clock.advance(Duration.ofMinutes(6));

            assertThat(keysFor("key-a")).singleElement().extracting(JWK::getKeyID).isEqualTo("key-a");
            assertThat(keycloak.calls()).isEqualTo(2);
            assertThat(eventsOf(JwksEvents.ReloadFailed.class)).singleElement().satisfies(failed -> {
                assertThat(failed.trigger()).isEqualTo(JwksEvents.Trigger.REFRESH);
                assertThat(failed.outage()).isZero();
                assertThat(failed.servingLastKnown()).isTrue();
                assertThat(failed.cause()).isInstanceOf(ConnectException.class);
            });
        }

        @Test
        @DisplayName("retries at most once per minimum reload interval")
        void retriesAreSpaced() throws KeySourceException {
            keycloak.serve(keyA);
            keysFor("key-a");
            keycloak.failWith(new ConnectException("Connection refused"));
            clock.advance(Duration.ofMinutes(6));
            keysFor("key-a");

            clock.advance(Duration.ofSeconds(29));
            keysFor("key-a");
            assertThat(keycloak.calls()).isEqualTo(2);

            clock.advance(Duration.ofSeconds(1));
            keysFor("key-a");
            assertThat(keycloak.calls()).isEqualTo(3);
            assertThat(eventsOf(JwksEvents.ReloadFailed.class)).last()
                    .extracting(JwksEvents.ReloadFailed::outage).isEqualTo(Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("is counted from the first failure, not from the age of the keys: an idle night costs nothing")
        void countedFromTheFirstFailure() throws KeySourceException {
            keycloak.serve(keyA);
            keysFor("key-a");

            keycloak.failWith(new ConnectException("Connection refused"));
            clock.advance(Duration.ofHours(30));

            assertThat(keysFor("key-a")).hasSize(1);
        }

        @Test
        @DisplayName("refuses tokens once it lasts the outage tolerance, rather than trusting keys forever")
        void beyondTolerance() throws KeySourceException {
            keycloak.serve(keyA);
            keysFor("key-a");
            keycloak.failWith(new ConnectException("Connection refused"));
            clock.advance(Duration.ofMinutes(5));
            keysFor("key-a");

            clock.advance(Duration.ofHours(24));

            assertThatThrownBy(() -> keysFor("key-a"))
                    .isInstanceOf(RemoteKeySourceException.class)
                    .hasMessageContaining("outage tolerance")
                    .hasCauseInstanceOf(ConnectException.class);
            assertThat(eventsOf(JwksEvents.ReloadFailed.class)).last().satisfies(failed -> {
                assertThat(failed.servingLastKnown()).isFalse();
                assertThat(failed.outage()).isEqualTo(Duration.ofHours(24));
            });
        }

        @Test
        @DisplayName("ends with the first successful reload, which says the outage is over")
        void recovery() throws KeySourceException {
            keycloak.serve(keyA);
            keysFor("key-a");
            keycloak.failWith(new ConnectException("Connection refused"));
            clock.advance(Duration.ofMinutes(6));
            keysFor("key-a");

            keycloak.serve(keyA, keyB);
            clock.advance(Duration.ofSeconds(30));

            assertThat(keysFor("key-b")).hasSize(1);
            assertThat(eventsOf(JwksEvents.KeySetLoaded.class)).last()
                    .isEqualTo(new JwksEvents.KeySetLoaded(JwksEvents.Trigger.REFRESH, 2, true));

            keycloak.failWith(new ConnectException("Connection refused"));
            clock.advance(Duration.ofMinutes(5));
            keysFor("key-a");
            assertThat(eventsOf(JwksEvents.ReloadFailed.class)).last()
                    .extracting(JwksEvents.ReloadFailed::outage).isEqualTo(Duration.ZERO);
        }
    }

    @Nested
    @DisplayName("an unknown kid")
    class UnknownKey {

        @Test
        @DisplayName("reloads at once, before the refresh interval: a rotation is not refused for five minutes")
        void rotation() throws KeySourceException {
            keycloak.serve(keyA);
            keysFor("key-a");

            keycloak.serve(keyA, keyB);
            clock.advance(Duration.ofSeconds(30));

            assertThat(keysFor("key-b")).singleElement().extracting(JWK::getKeyID).isEqualTo("key-b");
            assertThat(keycloak.calls()).isEqualTo(2);
            assertThat(eventsOf(JwksEvents.KeySetLoaded.class)).last()
                    .isEqualTo(new JwksEvents.KeySetLoaded(JwksEvents.Trigger.UNKNOWN_KEY, 2, false));
        }

        @Test
        @DisplayName("reloads no more than once per minimum interval, so made-up kids cannot flood Keycloak")
        void spaced() throws KeySourceException {
            keycloak.serve(keyA);
            keysFor("key-a");

            clock.advance(Duration.ofSeconds(29));
            assertThat(keysFor("made-up")).isEmpty();
            assertThat(keycloak.calls()).isEqualTo(1);

            clock.advance(Duration.ofSeconds(1));
            assertThat(keysFor("made-up")).isEmpty();
            assertThat(keysFor("made-up-too")).isEmpty();
            assertThat(keycloak.calls()).isEqualTo(2);
        }

        @Test
        @DisplayName("that cannot be reloaded is an infrastructure failure, never a refusal of the token")
        void unreloadable() throws KeySourceException {
            keycloak.serve(keyA);
            keysFor("key-a");

            keycloak.failWith(new ConnectException("Connection refused"));
            clock.advance(Duration.ofSeconds(30));

            assertThatThrownBy(() -> keysFor("key-b"))
                    .isInstanceOf(RemoteKeySourceException.class)
                    .hasMessage("No loaded key matches the token and the JWK set cannot be reloaded");
            assertThat(eventsOf(JwksEvents.ReloadFailed.class)).singleElement()
                    .extracting(JwksEvents.ReloadFailed::trigger).isEqualTo(JwksEvents.Trigger.UNKNOWN_KEY);
            assertThat(keysFor("key-a")).hasSize(1);

            clock.advance(Duration.ofSeconds(10));
            assertThatThrownBy(() -> keysFor("key-b")).isInstanceOf(RemoteKeySourceException.class);
            assertThat(keycloak.calls()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("a response that is not a usable key set")
    class UnusableResponse {

        @Test
        @DisplayName("a set without keys does not replace good keys")
        void emptySet() throws KeySourceException {
            keycloak.serve(keyA);
            keysFor("key-a");

            keycloak.serveRaw("{\"keys\":[]}");
            clock.advance(Duration.ofMinutes(5));

            assertThat(keysFor("key-a")).hasSize(1);
            assertThat(eventsOf(JwksEvents.ReloadFailed.class)).singleElement()
                    .extracting(JwksEvents.ReloadFailed::cause).isInstanceOf(ParseException.class);
        }

        @Test
        @DisplayName("a body that is not JSON does not either")
        void malformed() throws KeySourceException {
            keycloak.serve(keyA);
            keysFor("key-a");

            keycloak.serveRaw("<html>Bad gateway</html>");
            clock.advance(Duration.ofMinutes(5));

            assertThat(keysFor("key-a")).hasSize(1);
            assertThat(eventsOf(JwksEvents.ReloadFailed.class)).singleElement()
                    .extracting(JwksEvents.ReloadFailed::cause).isInstanceOf(ParseException.class);
        }

        @Test
        @DisplayName("an unexpected exception of the retriever is a failed reload too")
        void runtimeFailure() throws KeySourceException {
            keycloak.serve(keyA);
            keysFor("key-a");

            keycloak.failWith(new IllegalStateException("unexpected"));
            clock.advance(Duration.ofMinutes(5));

            assertThat(keysFor("key-a")).hasSize(1);
        }
    }

    @Nested
    @DisplayName("concurrent requests")
    class Concurrency {

        @Test
        @DisplayName("are not held by a scheduled reload in flight: they keep the current set")
        void scheduledReloadDoesNotHold() throws Exception {
            keycloak.serve(keyA);
            keysFor("key-a");
            clock.advance(Duration.ofMinutes(5));
            CountDownLatch release = keycloak.blockNextCall();

            CompletableFuture<List<JWK>> reloading = CompletableFuture.supplyAsync(() -> unchecked("key-a"));
            keycloak.awaitBlockedCall();

            assertThat(keysFor("key-a")).hasSize(1);
            assertThat(reloading).isNotDone();

            release.countDown();
            assertThat(reloading.get(5, TimeUnit.SECONDS)).hasSize(1);
            assertThat(keycloak.calls()).isEqualTo(2);
        }

        @Test
        @DisplayName("with nothing loaded yet, wait for the load in flight and share its result")
        void firstLoadShared() throws Exception {
            CountDownLatch release = keycloak.blockNextCall();
            keycloak.serve(keyA);

            CompletableFuture<List<JWK>> first = CompletableFuture.supplyAsync(() -> unchecked("key-a"));
            keycloak.awaitBlockedCall();
            Thread second = new Thread(() -> unchecked("key-a"));
            second.start();
            awaitParked(second);

            release.countDown();
            second.join(5000);
            assertThat(first.get(5, TimeUnit.SECONDS)).hasSize(1);
            assertThat(keycloak.calls()).isEqualTo(1);
        }

        private List<JWK> unchecked(String kid) {
            try {
                return keysFor(kid);
            } catch (KeySourceException e) {
                throw new IllegalStateException(e);
            }
        }

        private static void awaitParked(Thread thread) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertThat(thread.getState()).isEqualTo(Thread.State.WAITING);
        }
    }

    @Nested
    @DisplayName("its settings")
    class Settings {

        @Test
        @DisplayName("must be positive durations")
        void positive() {
            RestJwtProperties.Jwks zeroRefresh = new RestJwtProperties.Jwks();
            zeroRefresh.setRefreshInterval(Duration.ZERO);
            RestJwtProperties.Jwks negativeReload = new RestJwtProperties.Jwks();
            negativeReload.setMinReloadInterval(Duration.ofSeconds(-1));
            RestJwtProperties.Jwks noTolerance = new RestJwtProperties.Jwks();
            noTolerance.setOutageTolerance(null);

            assertThatThrownBy(() -> sourceWith(zeroRefresh)).hasMessageContaining("refresh-interval");
            assertThatThrownBy(() -> sourceWith(negativeReload)).hasMessageContaining("min-reload-interval");
            assertThatThrownBy(() -> sourceWith(noTolerance)).hasMessageContaining("outage-tolerance");
        }

        private FaultTolerantJwkSource sourceWith(RestJwtProperties.Jwks settings) {
            return new FaultTolerantJwkSource(url(), keycloak, settings, clock, events::add);
        }
    }

    private static URL url() {
        try {
            return URI.create("http://keycloak:8080/realms/ecclesiaflow/protocol/openid-connect/certs").toURL();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Answers like Keycloak's certs endpoint, or fails the way the network does; counts the calls. */
    private static final class StubKeycloak implements ResourceRetriever {

        private final AtomicInteger calls = new AtomicInteger();
        private volatile String body;
        private volatile Exception failure;
        private volatile CountDownLatch release;
        private final CountDownLatch blocked = new CountDownLatch(1);

        void serve(JWK... keys) {
            serveRaw(new JWKSet(List.of(keys)).toPublicJWKSet().toString());
        }

        void serveRaw(String json) {
            body = json;
            failure = null;
        }

        void failWith(Exception cause) {
            failure = cause;
        }

        CountDownLatch blockNextCall() {
            release = new CountDownLatch(1);
            return release;
        }

        void awaitBlockedCall() throws InterruptedException {
            assertThat(blocked.await(5, TimeUnit.SECONDS)).isTrue();
        }

        int calls() {
            return calls.get();
        }

        @Override
        public Resource retrieveResource(URL url) throws IOException {
            calls.incrementAndGet();
            CountDownLatch gate = release;
            if (gate != null) {
                release = null;
                blocked.countDown();
                try {
                    gate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            Exception cause = failure;
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            return new Resource(body, "application/json");
        }
    }

    private static final class MutableClock extends Clock {

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
