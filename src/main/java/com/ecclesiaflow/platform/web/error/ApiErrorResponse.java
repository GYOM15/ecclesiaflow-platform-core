package com.ecclesiaflow.platform.web.error;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import java.time.Instant;
import java.util.List;

/**
 * The superset of the shapes the services already emit, so adopting it only adds fields; the
 * timestamp is the UTC instant the contracts document. Clients branch on errorCode, never message.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant timestamp,
        int status,
        String error,
        String errorCode,
        String message,
        String path,
        List<ApiValidationError> errors) {

    public ApiErrorResponse {
        errors = errors == null ? null : List.copyOf(errors);
    }

    public static ApiErrorResponse of(Instant timestamp, HttpStatusCode status, String errorCode,
                                      String message, String path) {
        HttpStatus known = HttpStatus.resolve(status.value());
        String reason = known != null ? known.getReasonPhrase() : "Error";
        return new ApiErrorResponse(timestamp, status.value(), reason, errorCode, message, path, null);
    }

    public ApiErrorResponse withErrors(List<ApiValidationError> fieldErrors) {
        return new ApiErrorResponse(timestamp, status, error, errorCode, message, path, fieldErrors);
    }
}
