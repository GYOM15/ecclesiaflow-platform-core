package com.ecclesiaflow.platform.security.reauth.code.web;

import com.ecclesiaflow.platform.security.reauth.PendingConfirmation;
import com.ecclesiaflow.platform.security.reauth.code.ConfirmationCodeSentException;
import com.ecclesiaflow.platform.security.reauth.code.InvalidReauthCodeException;
import com.ecclesiaflow.platform.security.reauth.code.ReauthCodeExpiredException;
import com.ecclesiaflow.platform.security.reauth.code.ReauthCodeLockedException;
import com.ecclesiaflow.platform.security.reauth.code.ReauthCodeNotSentException;
import com.ecclesiaflow.platform.web.error.ApiErrorResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EmailCodeExceptionHandlerTest {

    private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");

    private final EmailCodeExceptionHandler handler =
            new EmailCodeExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC)) { };
    private final MockHttpServletRequest request =
            new MockHttpServletRequest("POST", "/ecclesiaflow/billing/me/subscription/cancellation");

    private static void assertBody(ResponseEntity<ApiErrorResponse> answer, HttpStatus status, String errorCode) {
        assertThat(answer.getStatusCode()).isEqualTo(status);
        assertThat(answer.getBody()).isNotNull().satisfies(body -> {
            assertThat(body.status()).isEqualTo(status.value());
            assertThat(body.errorCode()).isEqualTo(errorCode);
            assertThat(body.timestamp()).isEqualTo(NOW);
            assertThat(body.path()).isEqualTo("/ecclesiaflow/billing/me/subscription/cancellation");
        });
    }

    @Test
    @DisplayName("a wrong or malformed code is 400 REAUTH_CODE_INVALID")
    void invalid() {
        assertBody(handler.handleInvalid(InvalidReauthCodeException.wrongCode(), request), HttpStatus.BAD_REQUEST,
                "REAUTH_CODE_INVALID");
        assertBody(handler.handleInvalid(InvalidReauthCodeException.malformedProof(), request),
                HttpStatus.BAD_REQUEST, "REAUTH_CODE_INVALID");
    }

    @Test
    @DisplayName("a dead challenge is 410 REAUTH_CODE_EXPIRED")
    void expired() {
        assertBody(handler.handleExpired(new ReauthCodeExpiredException(), request), HttpStatus.GONE,
                "REAUTH_CODE_EXPIRED");
    }

    @Test
    @DisplayName("a locked account is 429 REAUTH_CODE_LOCKED with Retry-After")
    void locked() {
        ResponseEntity<ApiErrorResponse> answer = handler.handleLocked(new ReauthCodeLockedException(3600), request);

        assertBody(answer, HttpStatus.TOO_MANY_REQUESTS, "REAUTH_CODE_LOCKED");
        assertThat(answer.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("3600");
    }

    @Test
    @DisplayName("a code that could not leave is 503 SERVICE_UNAVAILABLE, the cause kept out of the body")
    void notSent() {
        ResponseEntity<ApiErrorResponse> answer = handler.handleNotSent(
                ReauthCodeNotSentException.deliveryFailed(new IllegalStateException("communication:9090 down")),
                request);

        assertBody(answer, HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE");
        assertThat(answer.getBody().message()).doesNotContain("communication");
    }

    @Test
    @DisplayName("a code sent from a delegate is the 202 PendingConfirmation, not an error")
    void codeSent() {
        PendingConfirmation pending = PendingConfirmation.codeSentToCurrentAddress(UUID.randomUUID(),
                NOW.plusSeconds(600), "c•••@gmail.com", NOW.plusSeconds(60));

        ResponseEntity<PendingConfirmation> answer = handler.handleCodeSent(new ConfirmationCodeSentException(pending));

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(answer.getBody()).isSameAs(pending);
    }

    @Test
    @DisplayName("is abstract, so a component scan reaching the library cannot register it")
    void abstractAdvice() {
        assertThat(Modifier.isAbstract(EmailCodeExceptionHandler.class.getModifiers())).isTrue();
    }

    @Test
    @DisplayName("the 202 exception carries no stack: it is an answer, not a failure")
    void noStack() {
        assertThat(new ConfirmationCodeSentException(PendingConfirmation.codeSentToCurrentAddress(UUID.randomUUID(),
                NOW, null, NOW)).getStackTrace()).isEmpty();
    }
}
