package com.ecclesiaflow.platform.idempotency.web;

import com.ecclesiaflow.platform.idempotency.CreationOutcome;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

import java.util.function.Function;

/**
 * The status and headers of an idempotent creation: the operation's own status when created, 200 and
 * {@code Idempotent-Replayed: true} when replayed, the body being the resource either way.
 */
public final class IdempotentResponses {

    private IdempotentResponses() {
    }

    /** A builder the delegate completes, with a {@code Location} for instance, then gives the body. */
    public static ResponseEntity.BodyBuilder status(CreationOutcome<?> outcome, HttpStatusCode createdStatus) {
        if (outcome.replayed()) {
            return ResponseEntity.ok().header(IdempotencyHeaders.REPLAYED, Boolean.TRUE.toString());
        }
        return ResponseEntity.status(createdStatus);
    }

    /** 201 with the resource mapped to its response body, or its replay. */
    public static <R, B> ResponseEntity<B> created(CreationOutcome<R> outcome, Function<? super R, B> toBody) {
        return status(outcome, HttpStatus.CREATED).body(toBody.apply(outcome.resource()));
    }
}
