package com.ecclesiaflow.platform.security.reauth.code.web;

import com.ecclesiaflow.platform.security.reauth.PendingConfirmation;
import com.ecclesiaflow.platform.security.reauth.code.ConfirmationCodeSentException;
import com.ecclesiaflow.platform.security.reauth.code.InvalidReauthCodeException;
import com.ecclesiaflow.platform.security.reauth.code.ReauthCodeExpiredException;
import com.ecclesiaflow.platform.security.reauth.code.ReauthCodeLockedException;
import com.ecclesiaflow.platform.security.reauth.code.ReauthCodeNotSentException;
import com.ecclesiaflow.platform.web.error.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Clock;

/**
 * Answers the e-mail code's outcomes in the shared error body, and the 202 of a gesture checked in its delegate.
 * Registered only in a module that enables the code, apart from the platform's fallback, whose handlers every
 * module's error-code registry samples: members and church gain no code. Ahead of a module's catch-all, which
 * would turn them into 500. Abstract so that a component scan reaching the library cannot register it on its own.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE - 1)
public abstract class EmailCodeExceptionHandler {

    public static final String REAUTH_CODE_INVALID = "REAUTH_CODE_INVALID";
    public static final String REAUTH_CODE_EXPIRED = "REAUTH_CODE_EXPIRED";
    public static final String REAUTH_CODE_LOCKED = "REAUTH_CODE_LOCKED";
    public static final String SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";

    private final Clock clock;

    protected EmailCodeExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(ConfirmationCodeSentException.class)
    public ResponseEntity<PendingConfirmation> handleCodeSent(ConfirmationCodeSentException ex) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ex.getPending());
    }

    @ExceptionHandler(InvalidReauthCodeException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalid(InvalidReauthCodeException ex, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, REAUTH_CODE_INVALID, "The confirmation code is incorrect.",
                new HttpHeaders(), request);
    }

    @ExceptionHandler(ReauthCodeExpiredException.class)
    public ResponseEntity<ApiErrorResponse> handleExpired(ReauthCodeExpiredException ex, HttpServletRequest request) {
        return respond(HttpStatus.GONE, REAUTH_CODE_EXPIRED, "The confirmation code is no longer valid.",
                new HttpHeaders(), request);
    }

    // Without the delay the console cannot tell the member when to try again.
    @ExceptionHandler(ReauthCodeLockedException.class)
    public ResponseEntity<ApiErrorResponse> handleLocked(ReauthCodeLockedException ex, HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()));
        return respond(HttpStatus.TOO_MANY_REQUESTS, REAUTH_CODE_LOCKED,
                "Too many incorrect confirmation codes. Try again later.", headers, request);
    }

    @ExceptionHandler(ReauthCodeNotSentException.class)
    public ResponseEntity<ApiErrorResponse> handleNotSent(ReauthCodeNotSentException ex, HttpServletRequest request) {
        return respond(HttpStatus.SERVICE_UNAVAILABLE, SERVICE_UNAVAILABLE,
                "A required service is temporarily unavailable. Try again later.", new HttpHeaders(), request);
    }

    private ResponseEntity<ApiErrorResponse> respond(HttpStatus status, String errorCode, String message,
                                                     HttpHeaders headers, HttpServletRequest request) {
        return ResponseEntity.status(status).headers(headers)
                .body(ApiErrorResponse.of(clock.instant(), status, errorCode, message, request.getRequestURI()));
    }
}
