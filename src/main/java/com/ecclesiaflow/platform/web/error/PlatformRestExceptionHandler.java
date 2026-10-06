package com.ecclesiaflow.platform.web.error;

import com.ecclesiaflow.platform.error.ErrorCategory;
import com.ecclesiaflow.platform.error.ErrorCategoryResolver;
import com.ecclesiaflow.platform.logging.SecurityMaskingUtils;
import com.ecclesiaflow.platform.ratelimit.RateLimitExceededException;
import com.ecclesiaflow.platform.upload.ImageDecodeCapacityExceededException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.DisconnectedClientHelper;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Lowest precedence, so a service's own handler keeps priority, its catch-all included. Abstract so a
 * component scan reaching the library (members scans com.ecclesiaflow) cannot register it on its own.
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public abstract class PlatformRestExceptionHandler {

    static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";
    static final String RATE_LIMIT_EXCEEDED = "RATE_LIMIT_EXCEEDED";
    static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private static final Set<String> SECURITY_EXCEPTIONS = Set.of(
            "org.springframework.security.access.AccessDeniedException",
            "org.springframework.security.core.AuthenticationException");

    private final ErrorCategoryResolver categories;
    private final Clock clock;

    protected PlatformRestExceptionHandler(ErrorCategoryResolver categories, Clock clock) {
        this.categories = categories;
        this.clock = clock;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidBody(MethodArgumentNotValidException ex,
                                                              HttpServletRequest request) {
        List<ApiValidationError> errors = ex.getBindingResult().getAllErrors().stream()
                .map(PlatformRestExceptionHandler::toValidationError)
                .toList();
        return invalid(errors, request);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidParameters(HandlerMethodValidationException ex,
                                                                    HttpServletRequest request) {
        if (ex.isForReturnValue()) {
            return internalError(ex, request);
        }
        List<ApiValidationError> errors = new ArrayList<>();
        for (ParameterValidationResult result : ex.getAllValidationResults()) {
            String parameter = parameterName(result);
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                String path = error instanceof FieldError field ? parameter + "." + field.getField() : parameter;
                errors.add(new ApiValidationError(path, error.getDefaultMessage(), constraintName(error)));
            }
        }
        for (MessageSourceResolvable error : ex.getCrossParameterValidationResults()) {
            errors.add(new ApiValidationError(null, error.getDefaultMessage(), constraintName(error)));
        }
        return invalid(errors, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(ConstraintViolationException ex,
                                                                      HttpServletRequest request) {
        List<ApiValidationError> errors = ex.getConstraintViolations().stream()
                .map(PlatformRestExceptionHandler::toValidationError)
                .sorted(Comparator.comparing(ApiValidationError::path, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
        return invalid(errors, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex,
                                                                 HttpServletRequest request) {
        return answer(ex, HttpStatus.BAD_REQUEST, MALFORMED_REQUEST,
                "Request body is missing or malformed.", new HttpHeaders(), request);
    }

    @ExceptionHandler(TypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(TypeMismatchException ex, HttpServletRequest request) {
        String message = ex.getPropertyName() != null
                ? "Parameter '" + ex.getPropertyName() + "' has an invalid value."
                : "A request parameter has an invalid value.";
        return answer(ex, HttpStatus.BAD_REQUEST, codeFor(HttpStatus.BAD_REQUEST), message,
                new HttpHeaders(), request);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleRateLimited(RateLimitExceededException ex,
                                                              HttpServletRequest request) {
        // Without the delay a client retries at once and turns the limit into a tight loop.
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()));
        return answer(ex, HttpStatus.TOO_MANY_REQUESTS, RATE_LIMIT_EXCEEDED,
                "Too many requests. Try again in " + ex.getRetryAfterSeconds() + " seconds.", headers, request);
    }

    @ExceptionHandler(ImageDecodeCapacityExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleImageDecoderSaturated(ImageDecodeCapacityExceededException ex,
                                                                        HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()));
        return answer(ex, HttpStatus.SERVICE_UNAVAILABLE, codeFor(HttpStatus.SERVICE_UNAVAILABLE),
                messageFor(ErrorCategory.UNAVAILABLE), headers, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnmapped(Exception ex, HttpServletRequest request,
                                                           HttpServletResponse response) throws Exception {
        if (belongsToFramework(ex, response)) {
            throw ex;
        }
        if (ex instanceof ErrorResponse framework) {
            HttpStatusCode status = framework.getStatusCode();
            String detail = framework.getBody().getDetail();
            String message = StringUtils.hasText(detail) ? detail : reasonOf(status);
            return answer(ex, status, codeFor(status), message, framework.getHeaders(), request);
        }
        ResponseStatus annotated = AnnotatedElementUtils.findMergedAnnotation(ex.getClass(), ResponseStatus.class);
        if (annotated != null) {
            String message = StringUtils.hasText(annotated.reason()) ? annotated.reason() : reasonOf(annotated.code());
            return answer(ex, annotated.code(), codeFor(annotated.code()), message, new HttpHeaders(), request);
        }
        ErrorCategory category = categories.resolve(ex);
        HttpStatus status = HttpStatus.valueOf(category.httpStatus());
        if (category == ErrorCategory.INTERNAL) {
            return internalError(ex, request);
        }
        if (category == ErrorCategory.ALREADY_EXISTS || category == ErrorCategory.ABORTED) {
            // These hide real bugs as easily as lost races, so they stay visible.
            log.warn("HTTP-ERROR: ⚠ {} {} conflicted — {}: {}", request.getMethod(), route(request),
                    ex.getClass().getSimpleName(), SecurityMaskingUtils.rootMessage(ex));
            return respond(status, codeFor(status), messageFor(category), new HttpHeaders(), request);
        }
        return answer(ex, status, codeFor(status), messageFor(category), new HttpHeaders(), request);
    }

    private ResponseEntity<ApiErrorResponse> internalError(Exception ex, HttpServletRequest request) {
        log.error("HTTP-ERROR: ❌ {} {} failed — {}: {}", request.getMethod(), route(request),
                ex.getClass().getSimpleName(), SecurityMaskingUtils.rootMessage(ex), ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR, messageFor(ErrorCategory.INTERNAL),
                new HttpHeaders(), request);
    }

    private static boolean belongsToFramework(Exception ex, HttpServletResponse response) {
        return response.isCommitted()
                || DisconnectedClientHelper.isClientDisconnectedException(ex)
                || isSecurityException(ex);
    }

    // By name, so a service without Spring Security on its classpath can still load this class.
    private static boolean isSecurityException(Throwable ex) {
        for (Class<?> type = ex.getClass(); type != null; type = type.getSuperclass()) {
            if (SECURITY_EXCEPTIONS.contains(type.getName())) {
                return true;
            }
        }
        return false;
    }

    private ResponseEntity<ApiErrorResponse> invalid(List<ApiValidationError> errors, HttpServletRequest request) {
        log.debug("HTTP-ERROR: {} {} rejected — {} invalid field(s)", request.getMethod(), route(request), errors.size());
        ApiErrorResponse body = ApiErrorResponse.of(clock.instant(), HttpStatus.BAD_REQUEST, VALIDATION_ERROR,
                "Request validation failed.", request.getRequestURI()).withErrors(errors);
        return ResponseEntity.badRequest().body(body);
    }

    private ResponseEntity<ApiErrorResponse> answer(Exception ex, HttpStatusCode status, String errorCode,
                                                    String message, HttpHeaders headers, HttpServletRequest request) {
        if (status.is5xxServerError()) {
            log.warn("HTTP-ERROR: ⚠ {} {} answered {} — {}: {}", request.getMethod(), route(request), status.value(),
                    ex.getClass().getSimpleName(), SecurityMaskingUtils.rootMessage(ex));
        } else {
            log.debug("HTTP-ERROR: {} {} answered {} — {}", request.getMethod(), route(request), status.value(),
                    ex.getClass().getSimpleName());
        }
        return respond(status, errorCode, message, headers, request);
    }

    private ResponseEntity<ApiErrorResponse> respond(HttpStatusCode status, String errorCode, String message,
                                                     HttpHeaders headers, HttpServletRequest request) {
        ApiErrorResponse body = ApiErrorResponse.of(clock.instant(), status, errorCode, message, request.getRequestURI());
        return ResponseEntity.status(status).headers(headers).body(body);
    }

    // The matched pattern, not the URI: the URI carries member and church ids.
    private static String route(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern != null ? pattern.toString() : "[unmatched route]";
    }

    private static String codeFor(HttpStatusCode status) {
        if (status.value() == HttpStatus.INTERNAL_SERVER_ERROR.value()) {
            return INTERNAL_ERROR;
        }
        HttpStatus known = HttpStatus.resolve(status.value());
        return known != null ? known.name() : "HTTP_" + status.value();
    }

    private static String reasonOf(HttpStatusCode status) {
        HttpStatus known = HttpStatus.resolve(status.value());
        return known != null ? known.getReasonPhrase() : "Error";
    }

    private static String messageFor(ErrorCategory category) {
        return switch (category) {
            case INVALID_ARGUMENT -> "The request is invalid.";
            case UNAUTHENTICATED -> "Authentication is required.";
            case PERMISSION_DENIED -> "Access to this resource is denied.";
            case NOT_FOUND -> "The requested resource was not found.";
            case ALREADY_EXISTS -> "The request conflicts with existing data.";
            case FAILED_PRECONDITION -> "The resource is not in a state that allows this operation.";
            case ABORTED -> "The resource was modified concurrently. Retry the operation.";
            case RESOURCE_EXHAUSTED -> "Too many requests. Try again later.";
            case UNAVAILABLE -> "A required service is temporarily unavailable. Try again later.";
            case INTERNAL -> "An unexpected error occurred.";
        };
    }

    private static ApiValidationError toValidationError(ObjectError error) {
        String path = error instanceof FieldError field ? field.getField() : error.getObjectName();
        return new ApiValidationError(path, error.getDefaultMessage(), error.getCode());
    }

    private static ApiValidationError toValidationError(ConstraintViolation<?> violation) {
        String code = violation.getConstraintDescriptor() == null ? null
                : violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
        return new ApiValidationError(String.valueOf(violation.getPropertyPath()), violation.getMessage(), code);
    }

    private static String parameterName(ParameterValidationResult result) {
        String name = result.getMethodParameter().getParameterName();
        return name != null ? name : "arg" + result.getMethodParameter().getParameterIndex();
    }

    private static String constraintName(MessageSourceResolvable error) {
        String[] codes = error.getCodes();
        return ObjectUtils.isEmpty(codes) ? null : codes[codes.length - 1];
    }
}
