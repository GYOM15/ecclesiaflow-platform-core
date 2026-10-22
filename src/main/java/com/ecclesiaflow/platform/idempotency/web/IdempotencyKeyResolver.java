package com.ecclesiaflow.platform.idempotency.web;

import com.ecclesiaflow.platform.contract.ContractOperation;
import com.ecclesiaflow.platform.idempotency.IdempotencyKey;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.server.RequestPath;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Hands a delegate the request's {@code Idempotency-Key} only when the operation it reaches carries
 * {@code x-ef-idempotency: required}. The header on any other operation is ignored, so the generated
 * {@code idempotencyKey} argument must never be passed to the creator directly.
 */
public final class IdempotencyKeyResolver {

    // UUID.fromString also takes short groups such as 1-2-3-4-5, which the contract's format: uuid does not.
    private static final Pattern CANONICAL_UUID =
            Pattern.compile("\\p{XDigit}{8}-\\p{XDigit}{4}-\\p{XDigit}{4}-\\p{XDigit}{4}-\\p{XDigit}{12}");

    private final IdempotencyIndex index;

    public IdempotencyKeyResolver(IdempotencyIndex index) {
        this.index = index;
    }

    /**
     * @return empty when the operation does not run the primitive or the request carries no key
     * @throws InvalidIdempotencyKeyException when the operation runs the primitive and the header is not
     *                                        exactly one canonical UUID
     */
    public Optional<IdempotencyKey> resolve(HttpServletRequest request) {
        String path = RequestPath.parse(request.getRequestURI(), request.getContextPath())
                .pathWithinApplication().value();
        Optional<ContractOperation> operation = index.find(request.getMethod(), path);
        if (operation.isEmpty()) {
            return Optional.empty();
        }
        List<String> values = Collections.list(request.getHeaders(IdempotencyHeaders.KEY));
        if (values.isEmpty()) {
            return Optional.empty();
        }
        String value = values.get(0).strip();
        if (values.size() > 1 || !CANONICAL_UUID.matcher(value).matches()) {
            throw new InvalidIdempotencyKeyException();
        }
        return Optional.of(new IdempotencyKey(UUID.fromString(value), operation.get().label()));
    }

    /**
     * {@link #resolve(HttpServletRequest)} on the request the current thread serves.
     *
     * @throws IllegalStateException outside a servlet request
     */
    public Optional<IdempotencyKey> current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servlet)) {
            throw new IllegalStateException("No servlet request is bound to this thread");
        }
        return resolve(servlet.getRequest());
    }
}
