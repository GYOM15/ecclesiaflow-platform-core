package com.ecclesiaflow.platform.rpc.s2s.token;

import com.ecclesiaflow.platform.rpc.s2s.S2sProperties;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Public entry point for callers that need a valid s2s access token.
 *
 * <p><strong>Single responsibility:</strong> orchestrate {@link S2sTokenCache}
 * and {@link S2sTokenClient}. No HTTP, no parsing, no logging — those are
 * delegated, in line with the platform's "logs in aspects only" rule.</p>
 *
 * <p>Refresh model:
 * <ul>
 *   <li>One exchange with Keycloak at a time, run off the caller's thread; every caller
 *       that needs it waits on that same exchange, each for no longer than its own bound
 *       (the remaining gRPC deadline, or {@link #DEFAULT_MAX_WAIT}).</li>
 *   <li>Inside the refresh window a still-valid token is served at once while the
 *       refresh runs in the background.</li>
 *   <li>A failed exchange is not retried before a short backoff (1 s, doubling up to 5 s):
 *       callers with no usable token fail fast instead of queueing behind new attempts.</li>
 *   <li>The refresh window is the configured leeway, capped at half the token's lifetime,
 *       so a short-lived token is still reused.</li>
 * </ul>
 */
public class S2sTokenProvider {

    /** How long a caller without a deadline waits for a refresh in flight. */
    public static final Duration DEFAULT_MAX_WAIT = Duration.ofSeconds(10);

    private static final Duration FIRST_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(5);

    private final S2sTokenClient client;
    private final S2sTokenCache cache;
    private final int refreshLeewaySeconds;
    private final Clock clock;
    private final Executor refreshExecutor;
    private final AtomicReference<CompletableFuture<S2sToken>> inFlight = new AtomicReference<>();
    private volatile int effectiveLeewaySeconds;
    private volatile Failure lastFailure;

    public S2sTokenProvider(S2sTokenClient client, S2sTokenCache cache, S2sProperties props) {
        this(client, cache, props, Clock.systemUTC());
    }

    public S2sTokenProvider(S2sTokenClient client, S2sTokenCache cache, S2sProperties props, Clock clock) {
        this(client, cache, props, clock,
                task -> Thread.ofVirtual().name("s2s-token-refresh").start(task));
    }

    S2sTokenProvider(S2sTokenClient client, S2sTokenCache cache, S2sProperties props, Clock clock,
                     Executor refreshExecutor) {
        this.client = client;
        this.cache = cache;
        this.refreshLeewaySeconds = props.getRefreshLeewaySeconds();
        this.effectiveLeewaySeconds = refreshLeewaySeconds;
        this.clock = clock;
        this.refreshExecutor = refreshExecutor;
    }

    /**
     * Returns a currently valid access token, waiting at most {@link #DEFAULT_MAX_WAIT}
     * for a refresh.
     *
     * @throws S2sTokenException if no token can be obtained in time
     */
    public String getToken() {
        return getToken(DEFAULT_MAX_WAIT);
    }

    /**
     * Returns a currently valid access token, waiting at most {@code maxWait} for a refresh.
     *
     * @throws S2sTokenException if no token can be obtained within {@code maxWait}, or the
     *                           token endpoint failed within the current backoff
     */
    public String getToken(Duration maxWait) {
        Optional<S2sToken> fresh = cache.getIfValid(effectiveLeewaySeconds);
        if (fresh.isPresent()) {
            return fresh.get().accessToken();
        }
        Optional<S2sToken> stillValid = cache.getIfValid(0);
        if (stillValid.isPresent()) {
            if (!inBackoff()) {
                refresh();
            }
            return stillValid.get().accessToken();
        }
        Failure failure = lastFailure;
        if (failure != null && failure.blocks(clock.instant())) {
            throw new S2sTokenException("s2s token endpoint failed moments ago; next attempt after "
                    + failure.retryAfter(), failure.cause());
        }
        return await(refresh(), maxWait).accessToken();
    }

    /**
     * Drops the cached token, so the next call fetches a fresh one. Called when a server
     * refuses the token, e.g. after a key rotation.
     */
    public void invalidate() {
        cache.invalidate();
    }

    private boolean inBackoff() {
        Failure failure = lastFailure;
        return failure != null && failure.blocks(clock.instant());
    }

    private CompletableFuture<S2sToken> refresh() {
        while (true) {
            CompletableFuture<S2sToken> running = inFlight.get();
            if (running != null) {
                return running;
            }
            CompletableFuture<S2sToken> mine = new CompletableFuture<>();
            if (inFlight.compareAndSet(null, mine)) {
                // A refresh may have completed between the caller's cache read and this slot.
                Optional<S2sToken> landed = cache.getIfValid(effectiveLeewaySeconds);
                if (landed.isPresent()) {
                    inFlight.compareAndSet(mine, null);
                    mine.complete(landed.get());
                    return mine;
                }
                try {
                    refreshExecutor.execute(() -> fetchInto(mine));
                } catch (RuntimeException rejected) {
                    inFlight.compareAndSet(mine, null);
                    mine.completeExceptionally(new S2sTokenException("Could not start the s2s token refresh", rejected));
                }
                return mine;
            }
        }
    }

    private void fetchInto(CompletableFuture<S2sToken> result) {
        try {
            S2sToken token = client.fetchToken();
            long lifetimeSeconds = Duration.between(clock.instant(), token.expiry()).toSeconds();
            effectiveLeewaySeconds = (int) Math.max(0, Math.min(refreshLeewaySeconds, lifetimeSeconds / 2));
            cache.put(token);
            lastFailure = null;
            inFlight.compareAndSet(result, null);
            result.complete(token);
        } catch (RuntimeException e) {
            S2sTokenException cause = e instanceof S2sTokenException tokenFailure
                    ? tokenFailure
                    : new S2sTokenException("s2s token refresh failed", e);
            lastFailure = nextFailure(cause);
            inFlight.compareAndSet(result, null);
            result.completeExceptionally(cause);
        }
    }

    private Failure nextFailure(S2sTokenException cause) {
        Failure previous = lastFailure;
        int consecutive = previous == null ? 1 : previous.consecutive() + 1;
        Duration backoff = FIRST_BACKOFF.multipliedBy(1L << Math.min(consecutive - 1, 3));
        if (backoff.compareTo(MAX_BACKOFF) > 0) {
            backoff = MAX_BACKOFF;
        }
        return new Failure(clock.instant().plus(backoff), consecutive, cause);
    }

    private static S2sToken await(CompletableFuture<S2sToken> refresh, Duration maxWait) {
        try {
            return refresh.get(Math.max(0, maxWait.toNanos()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            // The shared refresh keeps running: only this caller gives up.
            throw new S2sTokenException("No s2s token within " + maxWait.toMillis() + " ms");
        } catch (ExecutionException e) {
            throw new S2sTokenException(e.getCause().getMessage(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new S2sTokenException("Interrupted while waiting for the s2s token", e);
        }
    }

    private record Failure(Instant retryAfter, int consecutive, S2sTokenException cause) {

        boolean blocks(Instant now) {
            return now.isBefore(retryAfter);
        }
    }
}
