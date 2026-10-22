package com.ecclesiaflow.platform.idempotency.web;

import com.ecclesiaflow.platform.contract.ApiContract;
import com.ecclesiaflow.platform.contract.ContractOperation;
import com.ecclesiaflow.platform.contract.ContractParameter;
import com.ecclesiaflow.platform.contract.InvalidApiContractException;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The operations of the module's contract that run the idempotency primitive, read and checked once at
 * startup. Only {@code x-ef-idempotency: required} switches it on, never the header alone: church already
 * serves two other {@code Idempotency-Key} semantics (signup, finance) that must not change.
 */
public final class IdempotencyIndex {

    public static final String EXTENSION = "x-ef-idempotency";
    public static final String REQUIRED = "required";

    private static final String EXPECTED_HEADER = "an optional " + IdempotencyHeaders.KEY + " header beside it "
            + "(in: header, required: false, schema: {type: string, format: uuid})";

    private final ApiContract contract;
    private final Set<ContractOperation> idempotent = Collections.newSetFromMap(new IdentityHashMap<>());

    /** @throws InvalidApiContractException on the first operation that misdeclares the extension */
    public IdempotencyIndex(ApiContract contract) {
        this.contract = contract;
        for (ContractOperation operation : contract.operations()) {
            if (operation.extensions().containsKey(EXTENSION)) {
                check(operation);
                idempotent.add(operation);
            }
        }
    }

    /**
     * The operation a request reaches, when it runs the primitive.
     *
     * @param path the path within the application, as received
     */
    public Optional<ContractOperation> find(String method, String path) {
        return contract.find(method, path).filter(idempotent::contains);
    }

    /** How many operations run the primitive. */
    public int count() {
        return idempotent.size();
    }

    private static void check(ContractOperation operation) {
        Object value = operation.extensions().get(EXTENSION);
        if (!REQUIRED.equals(value)) {
            throw InvalidApiContractException.invalidExtension(operation, EXTENSION, value, REQUIRED);
        }
        // A creation is a POST; every other write is idempotent by nature and never carries a key.
        if (!"POST".equals(operation.method())) {
            throw InvalidApiContractException.invalidExtension(operation, EXTENSION, value,
                    "it on a POST creation only, found on " + operation.method());
        }
        // The key is unique per author; without a token there is no author to scope it to.
        if (operation.tokenless()) {
            throw InvalidApiContractException.invalidExtension(operation, EXTENSION, value,
                    "it on an operation that takes a token, found on a security: [] one");
        }
        ContractParameter header = operation.parameter("header", IdempotencyHeaders.KEY)
                .orElseThrow(() -> InvalidApiContractException.invalidExtension(operation, EXTENSION, value,
                        EXPECTED_HEADER + ", found none"));
        // A required header would refuse the web console and every app already installed.
        if (header.required()) {
            throw InvalidApiContractException.invalidExtension(operation, EXTENSION, value,
                    EXPECTED_HEADER + ", found it required");
        }
        Map<String, Object> schema = header.schema();
        if (!"string".equals(schema.get("type")) || !"uuid".equals(schema.get("format"))) {
            throw InvalidApiContractException.invalidExtension(operation, EXTENSION, value,
                    EXPECTED_HEADER + ", found the schema " + schema);
        }
    }
}
