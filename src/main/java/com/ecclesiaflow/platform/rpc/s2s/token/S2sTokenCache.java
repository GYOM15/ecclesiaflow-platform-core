package com.ecclesiaflow.platform.rpc.s2s.token;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Lock-free holder of the current s2s token; the provider keeps a single refresh in flight. */
public class S2sTokenCache {

    private final AtomicReference<S2sToken> ref = new AtomicReference<>();
    private final Clock clock;

    public S2sTokenCache() {
        this(Clock.systemUTC());
    }

    public S2sTokenCache(Clock clock) {
        this.clock = clock;
    }

    /** The cached token, if it is still valid more than {@code leewaySeconds} from now. */
    public Optional<S2sToken> getIfValid(int leewaySeconds) {
        S2sToken snapshot = ref.get();
        if (snapshot == null) {
            return Optional.empty();
        }
        if (clock.instant().plusSeconds(leewaySeconds).isBefore(snapshot.expiry())) {
            return Optional.of(snapshot);
        }
        return Optional.empty();
    }

    public void put(S2sToken token) {
        ref.set(token);
    }

    public void invalidate() {
        ref.set(null);
    }
}
