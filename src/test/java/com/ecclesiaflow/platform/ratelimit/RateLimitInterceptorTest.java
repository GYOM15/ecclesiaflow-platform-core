package com.ecclesiaflow.platform.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RateLimitInterceptorTest {

    private static final RateLimitRule IMPORT =
            RateLimitRule.perChurch("import", 3, Duration.ofMinutes(1));

    /** Its methods are only reached by reflection. */
    @SuppressWarnings("unused")
    static class Controller {
        @RateLimited("import")
        public void limited() { }

        @RateLimited("nobody-declared-this")
        public void limitedByAnUnknownRule() { }

        public void notLimited() { }
    }

    private RateLimiter limiter;
    private RateLimitSubjectResolver subjects;
    private RateLimitInterceptor interceptor;
    private HttpServletRequest request;
    private HttpServletResponse response;

    @BeforeEach
    void setUp() {
        limiter = mock(RateLimiter.class);
        subjects = mock(RateLimitSubjectResolver.class);
        RateLimitRuleRegistry registry = () -> Map.of("import", IMPORT);
        interceptor = new RateLimitInterceptor(limiter, registry, subjects);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
    }

    private HandlerMethod handler(String methodName) throws NoSuchMethodException {
        Method method = Controller.class.getMethod(methodName);
        return new HandlerMethod(new Controller(), method);
    }

    @Test
    @DisplayName("a method with no annotation is never counted")
    void ignoresAnUnannotatedMethod() throws Exception {
        assertThat(interceptor.preHandle(request, response, handler("notLimited"))).isTrue();
        verify(limiter, never()).consume(any(), anyString());
    }

    @Test
    @DisplayName("a handler that is not a method at all is left alone")
    void ignoresANonMethodHandler() {
        // Static resources and error dispatches arrive here too.
        assertThat(interceptor.preHandle(request, response, "a resource handler")).isTrue();
        verify(limiter, never()).consume(any(), anyString());
    }

    @Test
    @DisplayName("an annotated method is counted against the resolved subject")
    void countsAnAnnotatedMethod() throws Exception {
        when(subjects.resolve(RateLimitScope.PER_CHURCH)).thenReturn(Optional.of("church-1"));
        when(limiter.consume(IMPORT, "church-1")).thenReturn(RateLimitDecision.allowed(3, 2));

        assertThat(interceptor.preHandle(request, response, handler("limited"))).isTrue();

        verify(limiter).consume(IMPORT, "church-1");
        verify(response).setHeader("RateLimit-Limit", "3");
        verify(response).setHeader("RateLimit-Remaining", "2");
    }

    @Test
    @DisplayName("a refusal throws BEFORE the controller, carrying Retry-After")
    void refusesBeforeTheControllerRuns() throws Exception {
        // The expense must not be paid before the refusal: no transaction, no query, no fan-out.
        when(subjects.resolve(RateLimitScope.PER_CHURCH)).thenReturn(Optional.of("church-1"));
        when(limiter.consume(IMPORT, "church-1")).thenReturn(RateLimitDecision.refused(3, 42));

        assertThatThrownBy(() -> interceptor.preHandle(request, response, handler("limited")))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("import")
                .satisfies(refusal -> {
                    RateLimitExceededException exceeded = (RateLimitExceededException) refusal;
                    assertThat(exceeded.getRule()).isEqualTo("import");
                    assertThat(exceeded.getRetryAfterSeconds()).isEqualTo(42);
                });

        verify(response).setHeader("Retry-After", "42");
    }

    @Test
    @DisplayName("no resolvable subject: the call passes, nothing is counted")
    void letsThroughWhenThereIsNoSubject() throws Exception {
        // A shared subject would let one caller spend everyone's allowance; the operation's
        // own authorization refuses the unauthenticated call next.
        when(subjects.resolve(RateLimitScope.PER_CHURCH)).thenReturn(Optional.empty());

        assertThat(interceptor.preHandle(request, response, handler("limited"))).isTrue();
        verify(limiter, never()).consume(any(), anyString());
    }

    @Test
    @DisplayName("a method claiming an UNDECLARED rule fails loudly, it is not let through")
    void refusesToRunWithAnUnknownRule() throws Exception {
        // A limit everybody believes in that silently does not apply is worse than none.
        assertThatThrownBy(() ->
                interceptor.preHandle(request, response, handler("limitedByAnUnknownRule")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nobody-declared-this");
    }
}
