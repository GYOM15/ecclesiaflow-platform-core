package com.ecclesiaflow.platform.security.reauth;

import com.ecclesiaflow.platform.web.error.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Clock;

/**
 * Answers the two refusals of {@link RecentAuthPolicy} in the shared error body. Kept out of the platform's
 * fallback advice, whose handlers every module's error-code registry samples: only a module that guards a
 * gesture emits these codes. Ahead of a module's catch-all, which would turn them into 500. Abstract so that a
 * component scan reaching the library cannot register it on its own.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE - 1)
public abstract class RecentAuthExceptionHandler {

    public static final String REAUTH_REQUIRED = "REAUTH_REQUIRED";
    public static final String SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";

    private final Clock clock;

    protected RecentAuthExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(ReauthenticationRequiredException.class)
    public ResponseEntity<ApiErrorResponse> handleReauthenticationRequired(ReauthenticationRequiredException ex,
                                                                           HttpServletRequest request) {
        return respond(HttpStatus.FORBIDDEN, REAUTH_REQUIRED, "A recent sign-in is required.", request);
    }

    @ExceptionHandler(SignInMethodsUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleSignInMethodsUnavailable(SignInMethodsUnavailableException ex,
                                                                           HttpServletRequest request) {
        return respond(HttpStatus.SERVICE_UNAVAILABLE, SERVICE_UNAVAILABLE,
                "A required service is temporarily unavailable. Try again later.", request);
    }

    private ResponseEntity<ApiErrorResponse> respond(HttpStatus status, String errorCode, String message,
                                                     HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(ApiErrorResponse.of(clock.instant(), status, errorCode, message, request.getRequestURI()));
    }
}
