package com.ecclesiaflow.platform.idempotency.web;

/** Header names frozen by the mobile contract: installed apps send and read them as written here. */
public final class IdempotencyHeaders {

    /** The client's key, a UUID it draws once per creation and resends on every retry of it. */
    public static final String KEY = "Idempotency-Key";

    /** {@code true} on a response that replays an earlier creation; absent otherwise. */
    public static final String REPLAYED = "Idempotent-Replayed";

    private IdempotencyHeaders() {
    }
}
