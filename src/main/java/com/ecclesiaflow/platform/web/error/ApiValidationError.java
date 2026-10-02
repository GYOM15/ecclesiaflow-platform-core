package com.ecclesiaflow.platform.web.error;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * No rejected value on purpose: echoing input puts whatever the user typed, e-mails and tokens
 * included, into every proxy and client log.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiValidationError(String path, String message, String code) {
}
