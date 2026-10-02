package com.ecclesiaflow.platform.error;

import java.util.Map;
import java.util.Optional;

/**
 * Declared as a bean by a service so its own exceptions get a meaning in the shared gRPC and HTTP
 * mapping without its business layer depending on either transport. Consulted before the built-ins.
 */
@FunctionalInterface
public interface ExceptionClassifier {

    Optional<ErrorCategory> classify(Throwable error);

    /** The closest mapped superclass wins, so a subtype can be given its own category. */
    static ExceptionClassifier byType(Map<Class<? extends Throwable>, ErrorCategory> categories) {
        Map<Class<? extends Throwable>, ErrorCategory> copy = Map.copyOf(categories);
        return error -> {
            for (Class<?> type = error.getClass(); type != null; type = type.getSuperclass()) {
                ErrorCategory category = copy.get(type);
                if (category != null) {
                    return Optional.of(category);
                }
            }
            return Optional.empty();
        };
    }
}
