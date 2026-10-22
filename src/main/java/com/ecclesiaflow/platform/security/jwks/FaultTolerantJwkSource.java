package com.ecclesiaflow.platform.security.jwks;

import com.ecclesiaflow.platform.security.RestJwtProperties;
import com.ecclesiaflow.platform.security.jwks.events.JwksEvents;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.RemoteKeySourceException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.ResourceRetriever;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.net.URL;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The realm's signing keys, kept in memory and reloaded every refresh interval. Nimbus's default cache, which
 * Spring's decoder builds, drops the keys when a reload fails past their five minutes: a Keycloak restart then
 * refuses every valid token. Here a failed reload keeps the last loaded set in service for the outage tolerance,
 * and an unknown {@code kid} reloads at once, so a key rotation is not refused until the next refresh.
 *
 * <p>One reload runs at a time. While a scheduled one is in flight, other requests keep the current set instead
 * of queueing behind it; with no set yet, or for an unknown {@code kid}, they wait and share its result.</p>
 */
public class FaultTolerantJwkSource implements JWKSource<SecurityContext> {

    private final URL jwkSetUrl;
    private final ResourceRetriever retriever;
    private final Duration refreshInterval;
    private final Duration minReloadInterval;
    private final Duration outageTolerance;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final ReentrantLock reloading = new ReentrantLock();

    private volatile KeySetState state = KeySetState.NONE;

    public FaultTolerantJwkSource(URL jwkSetUrl, ResourceRetriever retriever, RestJwtProperties.Jwks settings,
                                  Clock clock, ApplicationEventPublisher events) {
        this.jwkSetUrl = Objects.requireNonNull(jwkSetUrl, "jwkSetUrl");
        this.retriever = Objects.requireNonNull(retriever, "retriever");
        this.refreshInterval = positive(settings.getRefreshInterval(), "refresh-interval");
        this.minReloadInterval = positive(settings.getMinReloadInterval(), "min-reload-interval");
        this.outageTolerance = positive(settings.getOutageTolerance(), "outage-tolerance");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    public List<JWK> get(JWKSelector selector, SecurityContext context) throws KeySourceException {
        KeySetState current = state;
        if (reloadDue(current, clock.instant())) {
            current = reload(current, current.loaded() ? JwksEvents.Trigger.REFRESH : JwksEvents.Trigger.FIRST_LOAD);
        }
        requireServable(current);
        List<JWK> matches = selector.select(current.keys());
        if (matches.isEmpty() && mayReloadForUnknownKey(current, clock.instant())) {
            current = reload(current, JwksEvents.Trigger.UNKNOWN_KEY);
            requireServable(current);
            matches = selector.select(current.keys());
        }
        // Refusing with 401 here would sign out a user whose token is valid but signed by a key we cannot fetch.
        if (matches.isEmpty() && current.failing()) {
            throw new RemoteKeySourceException("No loaded key matches the token and the JWK set cannot be reloaded",
                    current.failure());
        }
        return matches;
    }

    private boolean reloadDue(KeySetState current, Instant now) {
        return !current.loaded()
                || (!now.isBefore(current.loadedAt().plus(refreshInterval))
                && !now.isBefore(current.attemptedAt().plus(minReloadInterval)));
    }

    private boolean mayReloadForUnknownKey(KeySetState current, Instant now) {
        return !now.isBefore(current.attemptedAt().plus(minReloadInterval));
    }

    private void requireServable(KeySetState current) throws RemoteKeySourceException {
        if (!current.loaded()) {
            throw new RemoteKeySourceException("The JWK set could not be loaded", current.failure());
        }
        if (current.failing() && !clock.instant().isBefore(current.failingSince().plus(outageTolerance))) {
            throw new RemoteKeySourceException("The JWK set has not been reloadable for longer than the outage "
                    + "tolerance", current.failure());
        }
    }

    private KeySetState reload(KeySetState seen, JwksEvents.Trigger trigger) {
        if (trigger == JwksEvents.Trigger.REFRESH) {
            if (!reloading.tryLock()) {
                return seen;
            }
        } else {
            reloading.lock();
        }
        try {
            KeySetState latest = state;
            if (latest != seen) {
                return latest;
            }
            Instant now = clock.instant();
            KeySetState next;
            try {
                JWKSet keys = fetch();
                next = KeySetState.loaded(keys, now);
                state = next;
                events.publishEvent(new JwksEvents.KeySetLoaded(trigger, keys.size(), latest.failing()));
            } catch (IOException | ParseException | RuntimeException failure) {
                next = latest.failedAt(now, failure);
                state = next;
                boolean serving = next.loaded() && now.isBefore(next.failingSince().plus(outageTolerance));
                Duration outage = next.loaded() ? Duration.between(next.failingSince(), now) : null;
                events.publishEvent(new JwksEvents.ReloadFailed(trigger, failure, outage, serving));
            }
            return next;
        } finally {
            reloading.unlock();
        }
    }

    // A set without keys is a realm half started or misconfigured, not a rotation: it must not replace good keys.
    private JWKSet fetch() throws IOException, ParseException {
        JWKSet keys = JWKSet.parse(retriever.retrieveResource(jwkSetUrl).getContent());
        if (keys.isEmpty()) {
            throw new ParseException("The JWK set holds no key", 0);
        }
        return keys;
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(
                    "ecclesiaflow.rest.jwt.jwks." + name + " must be positive, was " + value);
        }
        return value;
    }

    /**
     * @param failingSince the first failed attempt after the last success; {@code null} while reloads succeed
     * @param failure      the last attempt's failure; {@code null} when it succeeded
     */
    private record KeySetState(JWKSet keys, Instant loadedAt, Instant attemptedAt, Instant failingSince,
                               Exception failure) {

        static final KeySetState NONE = new KeySetState(null, null, null, null, null);

        static KeySetState loaded(JWKSet keys, Instant now) {
            return new KeySetState(keys, now, now, null, null);
        }

        KeySetState failedAt(Instant now, Exception cause) {
            return new KeySetState(keys, loadedAt, now, failing() ? failingSince : now, cause);
        }

        boolean loaded() {
            return keys != null;
        }

        boolean failing() {
            return failure != null;
        }
    }
}
