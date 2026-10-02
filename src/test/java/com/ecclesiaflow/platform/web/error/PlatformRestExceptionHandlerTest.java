package com.ecclesiaflow.platform.web.error;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ecclesiaflow.platform.error.DataIntegrityTestData;
import com.ecclesiaflow.platform.error.ErrorCategory;
import com.ecclesiaflow.platform.error.ErrorCategoryResolver;
import com.ecclesiaflow.platform.error.ExceptionClassifier;
import com.ecclesiaflow.platform.ratelimit.RateLimitExceededException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Each case is a request that a service's catch-all answers 500 today.
 */
class PlatformRestExceptionHandlerTest {

    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");

    static class ChurchNotFoundException extends RuntimeException {
        ChurchNotFoundException(String message) {
            super(message);
        }
    }

    @ResponseStatus(value = HttpStatus.GONE, reason = "Invitation expired")
    static class InvitationExpiredException extends RuntimeException {
    }

    @ResponseStatus(HttpStatus.PAYMENT_REQUIRED)
    static class PaymentRequiredException extends RuntimeException {
    }

    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    static class LedgerSealedException extends RuntimeException {
    }

    record NewMember(String email) {
    }

    @RestController
    static class MembersController {

        private RuntimeException next;
        private Exception checked;

        @GetMapping("/members/{id}")
        String get(@PathVariable UUID id) throws Exception {
            if (checked != null) {
                throw checked;
            }
            if (next != null) {
                throw next;
            }
            return "member";
        }

        @PostMapping(path = "/members", consumes = MediaType.APPLICATION_JSON_VALUE)
        String create(@RequestBody NewMember member) {
            return "created";
        }

        @PutMapping("/members/{id}/photo")
        String photo(@PathVariable UUID id, @RequestPart("file") MultipartFile file) {
            return "stored";
        }

        @GetMapping("/members/{id}/export")
        void export(@PathVariable UUID id, HttpServletResponse response) throws IOException {
            response.getWriter().write("partial");
            response.flushBuffer();
            throw new IllegalStateException("stream broke");
        }

        public String validated(@NotBlank String name) {
            return name;
        }
    }

    private final MembersController controller = new MembersController();
    private final Logger logger = (Logger) LoggerFactory.getLogger(PlatformRestExceptionHandler.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ErrorCategoryResolver categories = new ErrorCategoryResolver(List.of(
                ExceptionClassifier.byType(Map.of(ChurchNotFoundException.class, ErrorCategory.NOT_FOUND))));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new PlatformRestExceptionHandler(categories, Clock.fixed(NOW, ZoneOffset.UTC)) {
                })
                .setMessageConverters(new StringHttpMessageConverter(), new MappingJackson2HttpMessageConverter())
                .build();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    private void failNextWith(RuntimeException error) {
        controller.next = error;
    }

    @Nested
    @DisplayName("framework exceptions keep their HTTP meaning")
    class Framework {

        @Test
        @DisplayName("an unknown route is 404")
        void unknownRoute() throws Exception {
            mvc.perform(get("/nowhere"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.error").value("Not Found"))
                    .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.path").value("/nowhere"))
                    .andExpect(jsonPath("$.timestamp").value("2026-10-02T10:00:00Z"));
        }

        @Test
        @DisplayName("a missing static resource is 404")
        void noResource() throws Exception {
            failNextWith(null);
            controller.checked = new NoResourceFoundException(HttpMethod.GET, "favicon.ico");

            mvc.perform(get("/members/" + UUID.randomUUID()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
        }

        @Test
        @DisplayName("a wrong method is 405 with the Allow header")
        void wrongMethod() throws Exception {
            mvc.perform(post("/members/" + UUID.randomUUID()))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(header().string(HttpHeaders.ALLOW, "GET"))
                    .andExpect(jsonPath("$.errorCode").value("METHOD_NOT_ALLOWED"));
        }

        @Test
        @DisplayName("an unsupported content type is 415")
        void unsupportedMediaType() throws Exception {
            mvc.perform(post("/members").contentType(MediaType.TEXT_PLAIN).content("x"))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_MEDIA_TYPE"));
        }

        @Test
        @DisplayName("an unreadable body is 400")
        void malformedBody() throws Exception {
            mvc.perform(post("/members").contentType(MediaType.APPLICATION_JSON).content("{\"email\":"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"))
                    .andExpect(jsonPath("$.message").value("Request body is missing or malformed."));
        }

        @Test
        @DisplayName("a path variable of the wrong type is 400 naming the parameter")
        void typeMismatch() throws Exception {
            mvc.perform(get("/members/not-a-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"))
                    .andExpect(jsonPath("$.message").value("Parameter 'id' has an invalid value."));
        }

        @Test
        @DisplayName("a missing multipart part is 400")
        void missingPart() throws Exception {
            mvc.perform(multipart(HttpMethod.PUT, "/members/" + UUID.randomUUID() + "/photo"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"))
                    .andExpect(jsonPath("$.message").value("Required part 'file' is not present."));
        }

        @Test
        @DisplayName("a ResponseStatusException keeps its status and reason")
        void responseStatusException() throws Exception {
            failNextWith(new ResponseStatusException(HttpStatus.I_AM_A_TEAPOT, "brewing"));

            mvc.perform(get("/members/" + UUID.randomUUID()))
                    .andExpect(status().isIAmATeapot())
                    .andExpect(jsonPath("$.message").value("brewing"));
        }

        @Test
        @DisplayName("a type mismatch without a parameter name still answers 400")
        void typeMismatchWithoutName() throws Exception {
            failNextWith(new org.springframework.beans.TypeMismatchException("x", Integer.class));

            mvc.perform(get("/members/" + UUID.randomUUID()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("A request parameter has an invalid value."));
        }

        @Test
        @DisplayName("a framework 5xx without detail falls back to the reason phrase, even for a non-standard code")
        void framework5xx() throws Exception {
            failNextWith(new ResponseStatusException(org.springframework.http.HttpStatusCode.valueOf(599)));

            mvc.perform(get("/members/" + UUID.randomUUID()))
                    .andExpect(status().is(599))
                    .andExpect(jsonPath("$.error").value("Error"))
                    .andExpect(jsonPath("$.errorCode").value("HTTP_599"))
                    .andExpect(jsonPath("$.message").value("Error"));
            assertThat(logs.list).singleElement()
                    .satisfies(line -> assertThat(line.getLevel()).isEqualTo(Level.WARN));
        }

        @Test
        @DisplayName("an exception annotated with a 5xx @ResponseStatus is logged above DEBUG")
        void annotatedServerError() throws Exception {
            failNextWith(new LedgerSealedException());

            mvc.perform(get("/members/" + UUID.randomUUID()))
                    .andExpect(status().isServiceUnavailable());
            assertThat(logs.list).singleElement()
                    .satisfies(line -> assertThat(line.getLevel()).isEqualTo(Level.WARN));
        }

        @Test
        @DisplayName("an exception annotated with @ResponseStatus but no reason uses the reason phrase")
        void annotatedWithoutReason() throws Exception {
            failNextWith(new PaymentRequiredException());

            mvc.perform(get("/members/" + UUID.randomUUID()))
                    .andExpect(status().isPaymentRequired())
                    .andExpect(jsonPath("$.message").value("Payment Required"));
        }

        @Test
        @DisplayName("an exception annotated with @ResponseStatus keeps its status and reason")
        void annotatedException() throws Exception {
            failNextWith(new InvitationExpiredException());

            mvc.perform(get("/members/" + UUID.randomUUID()))
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.errorCode").value("GONE"))
                    .andExpect(jsonPath("$.message").value("Invitation expired"));
        }
    }

    @Nested
    @DisplayName("validation failures are 400 with one entry per field")
    class Validation {

        @Test
        @DisplayName("an invalid request body")
        void invalidBody() throws Exception {
            BeanPropertyBindingResult result = new BeanPropertyBindingResult(new NewMember("x"), "newMember");
            result.addError(new FieldError("newMember", "email", "x", false,
                    new String[]{"Email.newMember.email", "Email"}, null, "must be a well-formed email address"));
            result.addError(new org.springframework.validation.ObjectError("newMember",
                    new String[]{"PasswordsMatch"}, null, "passwords do not match"));
            failNextWith(null);
            controller.checked = new MethodArgumentNotValidException(
                    new MethodParameter(MembersController.class.getDeclaredMethod("create", NewMember.class), 0), result);

            mvc.perform(get("/members/" + UUID.randomUUID()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.message").value("Request validation failed."))
                    .andExpect(jsonPath("$.errors[0].path").value("email"))
                    .andExpect(jsonPath("$.errors[0].message").value("must be a well-formed email address"))
                    .andExpect(jsonPath("$.errors[0].code").value("Email"))
                    .andExpect(jsonPath("$.errors[0].rejectedValue").doesNotExist())
                    .andExpect(jsonPath("$.errors[1].path").value("newMember"))
                    .andExpect(jsonPath("$.errors[1].code").value("PasswordsMatch"));
        }

        @Test
        @DisplayName("an invalid controller method parameter")
        void invalidParameter() throws Exception {
            MethodParameter name = new MethodParameter(MembersController.class.getMethod("validated", String.class), 0);
            name.initParameterNameDiscovery(new org.springframework.core.DefaultParameterNameDiscoverer());
            ParameterValidationResult blank = new ParameterValidationResult(name, "", List.of(
                    new DefaultMessageSourceResolvable(new String[]{"NotBlank.name", "NotBlank"}, "must not be blank")));
            ParameterValidationResult nested = new ParameterValidationResult(name, "", List.of(
                    new FieldError("name", "first", null, false, new String[]{"Size"}, null, "too long")));
            MethodParameter unnamed = new MethodParameter(MembersController.class.getMethod("validated", String.class), 0);
            ParameterValidationResult anonymous = new ParameterValidationResult(unnamed, "", List.of(
                    new DefaultMessageSourceResolvable(new String[]{"Pattern"}, "bad format")));
            failNextWith(new HandlerMethodValidationException(MethodValidationResult.create(controller,
                    name.getMethod(), List.of(blank, nested, anonymous),
                    List.of(new DefaultMessageSourceResolvable(null, null, "dates are inverted")))));

            mvc.perform(get("/members/" + UUID.randomUUID()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.errors[0].path").value("name"))
                    .andExpect(jsonPath("$.errors[0].code").value("NotBlank"))
                    .andExpect(jsonPath("$.errors[1].path").value("name.first"))
                    .andExpect(jsonPath("$.errors[2].path").value("arg0"))
                    .andExpect(jsonPath("$.errors[3].path").doesNotExist())
                    .andExpect(jsonPath("$.errors[3].code").doesNotExist())
                    .andExpect(jsonPath("$.errors[3].message").value("dates are inverted"));
        }

        @Test
        @DisplayName("an invalid return value is the server's fault: 500, not 400")
        void invalidReturnValue() throws Exception {
            MethodParameter returned = new MethodParameter(MembersController.class.getMethod("validated", String.class), -1);
            ParameterValidationResult invalid = new ParameterValidationResult(returned, "", List.of(
                    new DefaultMessageSourceResolvable(new String[]{"NotBlank"}, "must not be blank")));
            failNextWith(new HandlerMethodValidationException(
                    MethodValidationResult.create(controller, returned.getMethod(), List.of(invalid))));

            mvc.perform(get("/members/" + UUID.randomUUID()))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.errors").doesNotExist());
            assertThat(logs.list).singleElement()
                    .satisfies(line -> assertThat(line.getLevel()).isEqualTo(Level.ERROR));
        }

        @Test
        @DisplayName("a constraint violation from a validated service")
        @SuppressWarnings("unchecked")
        void constraintViolation() throws Exception {
            ConstraintViolation<Object> violation = mock(ConstraintViolation.class, Answers.RETURNS_DEEP_STUBS);
            when(violation.getPropertyPath().toString()).thenReturn("invite.email");
            when(violation.getMessage()).thenReturn("must not be blank");
            NotBlank notBlank = mock(NotBlank.class);
            when(notBlank.annotationType()).thenAnswer(invocation -> NotBlank.class);
            when(violation.getConstraintDescriptor().getAnnotation()).thenAnswer(invocation -> notBlank);
            ConstraintViolation<Object> classLevel = mock(ConstraintViolation.class, Answers.RETURNS_DEEP_STUBS);
            when(classLevel.getPropertyPath().toString()).thenReturn("");
            when(classLevel.getMessage()).thenReturn("invalid invitation");
            when(classLevel.getConstraintDescriptor()).thenReturn(null);
            failNextWith(new ConstraintViolationException(Set.of(violation, classLevel)));

            mvc.perform(get("/members/" + UUID.randomUUID()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.errors[0].path").value(""))
                    .andExpect(jsonPath("$.errors[0].code").doesNotExist())
                    .andExpect(jsonPath("$.errors[1].path").value("invite.email"))
                    .andExpect(jsonPath("$.errors[1].code").value("NotBlank"));
        }
    }

    @Nested
    @DisplayName("data and dependency failures")
    class DataAndDependencies {

        @Test
        @DisplayName("a unique-index race is 409 and does not echo the SQL")
        void uniqueViolation() throws Exception {
            failNextWith(DataIntegrityTestData.violation(DataIntegrityTestData.UNIQUE_VIOLATION,
                    "duplicate key value violates unique constraint \"uk_member_email\" Detail: (email)=(alice@church.org)"));

            mvc.perform(get("/members/" + UUID.randomUUID()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("CONFLICT"))
                    .andExpect(jsonPath("$.message").value("The request conflicts with existing data."));
            assertThat(logs.list).singleElement().satisfies(line -> {
                assertThat(line.getLevel()).isEqualTo(Level.WARN);
                assertThat(line.getFormattedMessage()).doesNotContain("alice@church.org");
            });
        }

        @Test
        @DisplayName("a foreign key violation is a bug: 500, opaque, logged once at ERROR")
        void foreignKeyViolation() throws Exception {
            failNextWith(DataIntegrityTestData.foreignKeyViolation());

            mvc.perform(get("/members/" + UUID.randomUUID()))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.message").value("An unexpected error occurred."));
            assertThat(logs.list).singleElement().satisfies(line -> {
                assertThat(line.getLevel()).isEqualTo(Level.ERROR);
                assertThat(line.getThrowableProxy()).isNotNull();
                assertThat(line.getFormattedMessage()).contains("DataIntegrityViolationException");
            });
        }

        @Test
        @DisplayName("a NOT NULL violation is a bug: 500, opaque, logged once at ERROR")
        void notNullViolation() throws Exception {
            failNextWith(DataIntegrityTestData.notNullViolation());

            mvc.perform(get("/members/" + UUID.randomUUID()))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.message").value("An unexpected error occurred."));
            assertThat(logs.list).singleElement().satisfies(line -> assertThat(line.getLevel()).isEqualTo(Level.ERROR));
        }

        @Test
        @DisplayName("a lost optimistic lock is 409")
        void optimisticLock() throws Exception {
            failNextWith(new OptimisticLockingFailureException("stale member"));

            mvc.perform(get("/members/" + UUID.randomUUID()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("The resource was modified concurrently. Retry the operation."));
        }

        @Test
        @DisplayName("a dependency outage is 503")
        void outage() throws Exception {
            failNextWith(new RedisConnectionFailureException("Unable to connect to redis:6379"));

            mvc.perform(get("/members/" + UUID.randomUUID()))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.errorCode").value("SERVICE_UNAVAILABLE"));
            assertThat(logs.list).singleElement().satisfies(line -> {
                assertThat(line.getLevel()).isEqualTo(Level.WARN);
                assertThat(line.getFormattedMessage()).doesNotContain("redis:6379");
            });
        }

        @Test
        @DisplayName("a rate-limit refusal is 429 with Retry-After")
        void rateLimited() throws Exception {
            failNextWith(new RateLimitExceededException("member-import", 42));

            mvc.perform(get("/members/" + UUID.randomUUID()))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "42"))
                    .andExpect(jsonPath("$.errorCode").value("RATE_LIMIT_EXCEEDED"))
                    .andExpect(jsonPath("$.message").value("Too many requests. Try again in 42 seconds."));
        }
    }

    @Nested
    @DisplayName("everything else")
    class Unmapped {

        @Test
        @DisplayName("an exception a module classified gets its category's status")
        void classified() throws Exception {
            failNextWith(new ChurchNotFoundException("church 42 is not here"));

            mvc.perform(get("/members/" + UUID.randomUUID()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value("The requested resource was not found."));
        }

        @org.junit.jupiter.params.ParameterizedTest
        @org.junit.jupiter.params.provider.EnumSource(ErrorCategory.class)
        @DisplayName("every category answers its HTTP status with a sober message")
        void everyCategory(ErrorCategory category) throws Exception {
            MockMvc classified = MockMvcBuilders.standaloneSetup(controller)
                    .setControllerAdvice(new PlatformRestExceptionHandler(new ErrorCategoryResolver(List.of(
                            error -> java.util.Optional.of(category))), Clock.fixed(NOW, ZoneOffset.UTC)) {
                    })
                    .build();
            failNextWith(new RuntimeException("probe"));

            classified.perform(get("/members/" + UUID.randomUUID()))
                    .andExpect(status().is(category.httpStatus()))
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }

        @Test
        @DisplayName("IllegalArgumentException is 400, as in every module")
        void illegalArgument() throws Exception {
            failNextWith(new IllegalArgumentException("bad"));

            mvc.perform(get("/members/" + UUID.randomUUID()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
        }

        @Test
        @DisplayName("an unexpected failure is 500, opaque, and logged once at ERROR with its stack trace")
        void unexpected() throws Exception {
            failNextWith(new IllegalStateException("upload to https://r2.internal/members failed"));

            mvc.perform(get("/members/" + UUID.randomUUID()))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.message").value("An unexpected error occurred."))
                    .andExpect(jsonPath("$.errors").doesNotExist());
            assertThat(logs.list).singleElement().satisfies(line -> {
                assertThat(line.getLevel()).isEqualTo(Level.ERROR);
                assertThat(line.getThrowableProxy()).isNotNull();
                assertThat(line.getFormattedMessage()).contains("GET /members/{id}").doesNotContain("r2.internal");
            });
        }

        @Test
        @DisplayName("a scanner hitting unknown routes does not fill the log above DEBUG")
        void unknownRouteIsQuiet() throws Exception {
            mvc.perform(get("/nowhere"));

            assertThat(logs.list).allSatisfy(line -> assertThat(line.getLevel()).isEqualTo(Level.DEBUG));
        }
    }

    @Nested
    @DisplayName("what belongs to someone else is left alone")
    class Deferred {

        @Test
        @DisplayName("Spring Security's refusal still reaches the security filter chain")
        void accessDenied() {
            failNextWith(new AccessDeniedException("missing MANAGE_MEMBERS"));

            assertThatThrownBy(() -> mvc.perform(get("/members/" + UUID.randomUUID())))
                    .hasRootCauseInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("a client that hung up is left to the framework: no error body, no log line")
        void clientGone() throws Exception {
            failNextWith(null);
            controller.checked = new IOException("Broken pipe");

            mvc.perform(get("/members/" + UUID.randomUUID()))
                    .andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEmpty());
            assertThat(logs.list).isEmpty();
        }

        @Test
        @DisplayName("a response already committed is not overwritten")
        void committed() {
            assertThatThrownBy(() -> mvc.perform(get("/members/" + UUID.randomUUID() + "/export")))
                    .hasRootCauseInstanceOf(IllegalStateException.class);
        }
    }
}
