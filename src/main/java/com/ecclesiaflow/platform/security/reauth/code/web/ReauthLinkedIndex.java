package com.ecclesiaflow.platform.security.reauth.code.web;

import com.ecclesiaflow.platform.contract.ApiContract;
import com.ecclesiaflow.platform.contract.ContractOperation;
import com.ecclesiaflow.platform.contract.ContractParameter;
import com.ecclesiaflow.platform.contract.InvalidApiContractException;
import com.ecclesiaflow.platform.security.reauth.code.ReauthHeaders;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The operations of the module's contract whose linked accounts confirm by e-mail code, read and checked once at
 * startup: {@code x-ef-reauth-linked: email-code} beside {@code x-ef-reauth: recent}, with the two optional headers
 * the replay carries. Clients read the mark to expect the 202 and its dialog; absent, a linked account confirms by
 * link, as elsewhere.
 */
public final class ReauthLinkedIndex {

    public static final String EXTENSION = "x-ef-reauth-linked";
    public static final String EMAIL_CODE = "email-code";
    static final String REAUTH_EXTENSION = "x-ef-reauth";
    static final String RECENT = "recent";

    private static final String EXPECTED_CHALLENGE = "an optional " + ReauthHeaders.CHALLENGE + " header "
            + "(in: header, required: false, schema: {type: string, format: uuid})";
    private static final String EXPECTED_CODE = "an optional " + ReauthHeaders.CODE + " header "
            + "(in: header, required: false, schema: {type: string, pattern: '" + ReauthHeaders.CODE_PATTERN + "'})";

    private final Set<String> labels = new TreeSet<>();

    /** @throws InvalidApiContractException on the first operation that misdeclares the mark */
    public ReauthLinkedIndex(ApiContract contract) {
        for (ContractOperation operation : contract.operations()) {
            if (operation.extensions().containsKey(EXTENSION)) {
                check(operation);
                labels.add(operation.label());
            }
        }
    }

    /** The labels of the marked operations, which name the gestures confirmed by code. */
    public Set<String> labels() {
        return Collections.unmodifiableSet(labels);
    }

    private static void check(ContractOperation operation) {
        Object value = operation.extensions().get(EXTENSION);
        if (!EMAIL_CODE.equals(value)) {
            throw InvalidApiContractException.invalidExtension(operation, EXTENSION, value, EMAIL_CODE);
        }
        if (!RECENT.equals(operation.extensions().get(REAUTH_EXTENSION))) {
            throw InvalidApiContractException.invalidExtension(operation, EXTENSION, value,
                    "it beside " + REAUTH_EXTENSION + ": " + RECENT + ", found " + REAUTH_EXTENSION + ": "
                            + operation.extensions().get(REAUTH_EXTENSION));
        }
        // The code is bound to the account of the token; without one there is nobody to send it to.
        if (operation.tokenless()) {
            throw InvalidApiContractException.invalidExtension(operation, EXTENSION, value,
                    "it on an operation that takes a token, found on a security: [] one");
        }
        checkHeader(operation, value, ReauthHeaders.CHALLENGE, EXPECTED_CHALLENGE, Map.of("format", "uuid"));
        checkHeader(operation, value, ReauthHeaders.CODE, EXPECTED_CODE, Map.of("pattern", ReauthHeaders.CODE_PATTERN));
    }

    // Required headers would refuse the first request of every gesture, which carries none.
    private static void checkHeader(ContractOperation operation, Object value, String name, String expected,
                                    Map<String, String> constraint) {
        ContractParameter header = operation.parameter("header", name)
                .orElseThrow(() -> InvalidApiContractException.invalidExtension(operation, EXTENSION, value,
                        expected + ", found none"));
        if (header.required()) {
            throw InvalidApiContractException.invalidExtension(operation, EXTENSION, value,
                    expected + ", found it required");
        }
        Map<String, Object> schema = header.schema();
        boolean constrained = constraint.entrySet().stream()
                .allMatch(entry -> entry.getValue().equals(schema.get(entry.getKey())));
        if (!"string".equals(schema.get("type")) || !constrained) {
            throw InvalidApiContractException.invalidExtension(operation, EXTENSION, value,
                    expected + ", found the schema " + schema);
        }
    }
}
