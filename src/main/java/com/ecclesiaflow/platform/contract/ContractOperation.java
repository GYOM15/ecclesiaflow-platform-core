package com.ecclesiaflow.platform.contract;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One operation of the module's OpenAPI contract.
 *
 * @param method       the HTTP method, upper case
 * @param pathTemplate the path as written under {@code paths}, e.g. {@code /ecclesiaflow/members/{memberId}}
 * @param operationId  the contract's {@code operationId}, {@code null} when it declares none
 * @param tokenless    {@code true} when the operation's effective {@code security} is {@code []}: its own, or the
 *                     root one it inherits. An operation that declares no security anywhere needs a token.
 * @param extensions   the operation's {@code x-*} keys and their values, path-item extensions excluded
 * @param parameters   path-item parameters merged with the operation's, the operation's winning
 * @param source       the contract file the operation was read from
 */
public record ContractOperation(String method,
                                String pathTemplate,
                                String operationId,
                                boolean tokenless,
                                Map<String, Object> extensions,
                                List<ContractParameter> parameters,
                                String source) {

    public ContractOperation {
        method = Objects.requireNonNull(method, "method").toUpperCase(Locale.ROOT);
        Objects.requireNonNull(pathTemplate, "pathTemplate");
        extensions = extensions == null ? Map.of() : ApiContractLoader.immutable(extensions);
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }

    /** Empty both when the key is absent and when its value is {@code null}; {@link #extensions()} tells them apart. */
    public Optional<Object> extension(String name) {
        return Optional.ofNullable(extensions.get(name));
    }

    /** See {@link ContractParameter#is(String, String)} for how names compare. */
    public Optional<ContractParameter> parameter(String in, String name) {
        return parameters.stream().filter(parameter -> parameter.is(in, name)).findFirst();
    }

    /** The {@code operationId} when there is one, else {@code METHOD /template}: what a log line or an error names. */
    public String label() {
        return operationId != null ? operationId : method + " " + pathTemplate;
    }
}
