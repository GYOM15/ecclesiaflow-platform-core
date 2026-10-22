package com.ecclesiaflow.platform.security.reauth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * A gesture held for confirmation, with what the annotated method received, so that the issuer can keep what
 * it needs to resume it (a new address, for instance).
 *
 * @param name      the annotation's {@code action}
 * @param subject   the {@code sub} of the token that asked
 * @param arguments the method's arguments, in order, {@code null} ones included
 */
public record SensitiveAction(String name, String subject, List<Object> arguments) {

    public SensitiveAction {
        arguments = arguments == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(arguments));
    }

    /** The first argument of that type. */
    public <T> Optional<T> argument(Class<T> type) {
        return arguments.stream().filter(type::isInstance).map(type::cast).findFirst();
    }
}
