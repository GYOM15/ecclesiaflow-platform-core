package com.ecclesiaflow.platform.idempotency.web;

import com.ecclesiaflow.platform.contract.ContractOperation;
import com.ecclesiaflow.platform.contract.ContractParameter;

import java.util.List;
import java.util.Map;

/** Operations shaped like the ones chat and church mark, and like church's legacy keyed operations. */
public final class IdempotencyTestContracts {

    public static final Map<String, Object> UUID_SCHEMA = Map.of("type", "string", "format", "uuid");

    private IdempotencyTestContracts() {
    }

    public static ContractParameter optionalKey() {
        return new ContractParameter(IdempotencyHeaders.KEY, "header", false, UUID_SCHEMA);
    }

    /** A creation marked {@code x-ef-idempotency: required}, with the optional header the rule asks for. */
    public static ContractOperation marked(String template, String operationId) {
        return operation("POST", template, operationId, false, IdempotencyIndex.REQUIRED, List.of(optionalKey()));
    }

    /** Church's signup and finance shape: the header declared, the extension absent. */
    public static ContractOperation legacy(String template, String operationId) {
        return operation("POST", template, operationId, false, null,
                List.of(new ContractParameter(IdempotencyHeaders.KEY, "header", true, Map.of("type", "string"))));
    }

    public static ContractOperation operation(String method, String template, String operationId, boolean tokenless,
                                              Object idempotency, List<ContractParameter> parameters) {
        Map<String, Object> extensions = idempotency == null
                ? Map.of()
                : Map.of(IdempotencyIndex.EXTENSION, idempotency);
        return new ContractOperation(method, template, operationId, tokenless, extensions, parameters, "test.yaml");
    }
}
